package com.jiku.messaging.internal

import com.jiku.shared.OwnWhatsAppNumberGate
import com.jiku.shared.TenantContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * Chooses the effective sender for the current tenant (JIKU-44): the tenant's
 * own provider when one is configured in the org settings, otherwise the
 * platform transport. A tenant's own WhatsApp number is used only while its
 * offer includes it (ADR 105); otherwise its sends go through the platform
 * number and its saved credentials wait for a renewal. Built adapters are cached per tenant and invalidated by
 * comparing the stored (encrypted) credentials — every save re-encrypts with a
 * fresh IV, so a settings change always rebuilds the adapter.
 */
@Component
class MessagingProviderResolver(
    private val repository: TenantProviderSettingsRepository,
    private val cipher: CredentialsCipher,
    private val objectMapper: ObjectMapper,
    private val restClientBuilder: RestClient.Builder,
    private val platformEmailSender: EmailSender,
    private val platformWhatsAppSender: WhatsAppSender,
    private val emailProperties: NotificationEmailProperties,
    private val ownNumberGate: OwnWhatsAppNumberGate,
    @Value("\${jiku.mail.resend.base-url:https://api.resend.com}") private val resendBaseUrl: String,
    @Value("\${jiku.whatsapp.meta.base-url:https://graph.facebook.com/v21.0}") private val metaBaseUrl: String,
) {
    data class ResolvedEmail(
        val sender: EmailSender,
        val from: String,
        val tenantOverride: Boolean,
    )

    data class ResolvedWhatsApp(
        val sender: WhatsAppSender,
        val tenantOverride: Boolean,
    )

    private data class CacheEntry(
        val encryptedCredentials: String,
        val sender: Any,
        val from: String?,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Transactional(readOnly = true)
    fun email(): ResolvedEmail {
        val row = activeRow(TenantProviderSettings.CHANNEL_EMAIL)
        if (row == null) {
            return ResolvedEmail(platformEmailSender, emailProperties.from, tenantOverride = false)
        }
        val entry =
            cached(TenantProviderSettings.CHANNEL_EMAIL, row.credentials) {
                val credentials = objectMapper.readValue(cipher.decrypt(row.credentials), ResendCredentials::class.java)
                val sender =
                    ResendEmailSender(
                        ResendEmailSender.buildClient(restClientBuilder.clone(), credentials.apiKey, resendBaseUrl),
                    )
                val from =
                    credentials.fromName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "$it <${credentials.from}>" }
                        ?: credentials.from
                CacheEntry(row.credentials, sender, from)
            }
        return ResolvedEmail(entry.sender as EmailSender, entry.from ?: emailProperties.from, tenantOverride = true)
    }

    @Transactional(readOnly = true)
    fun whatsApp(): ResolvedWhatsApp {
        val row = activeRow(TenantProviderSettings.CHANNEL_WHATSAPP)?.takeIf { ownNumberGate.ownNumberAllowed() }
        if (row == null) {
            return ResolvedWhatsApp(platformWhatsAppSender, tenantOverride = false)
        }
        val entry =
            cached(TenantProviderSettings.CHANNEL_WHATSAPP, row.credentials) {
                val credentials =
                    objectMapper.readValue(cipher.decrypt(row.credentials), MetaCloudCredentials::class.java)
                val sender =
                    MetaCloudWhatsAppSender.build(
                        builder = restClientBuilder.clone(),
                        accessToken = credentials.accessToken,
                        phoneNumberId = credentials.phoneNumberId,
                        baseUrl = metaBaseUrl,
                        templateName = credentials.templateName?.takeIf { it.isNotBlank() },
                        templateLanguage = credentials.templateLanguage,
                        buttonsTemplateName = credentials.buttonsTemplateName?.takeIf { it.isNotBlank() },
                        imageTemplateName = credentials.imageTemplateName?.takeIf { it.isNotBlank() },
                    )
                CacheEntry(row.credentials, sender, from = null)
            }
        return ResolvedWhatsApp(entry.sender as WhatsAppSender, tenantOverride = true)
    }

    private fun activeRow(channel: String): TenantProviderSettings? {
        TenantContext.get() ?: return null
        return repository.findByChannel(channel)
    }

    private fun cached(
        channel: String,
        encryptedCredentials: String,
        build: () -> CacheEntry,
    ): CacheEntry {
        val key = "${TenantContext.get()}:$channel"
        return cache.compute(key) { _, existing ->
            if (existing != null && existing.encryptedCredentials == encryptedCredentials) existing else build()
        }!!
    }
}
