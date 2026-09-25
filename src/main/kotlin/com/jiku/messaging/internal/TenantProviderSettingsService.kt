package com.jiku.messaging.internal

import com.jiku.shared.OwnWhatsAppNumberGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper

/**
 * Organizer-facing management of the tenant's own messaging providers
 * (JIKU-44): read the masked configuration, save credentials (encrypted at
 * rest), revert to the platform default, and fire a test send so the setup is
 * verifiable in one click. Raw secrets never leave this service.
 */
@Service
class TenantProviderSettingsService(
    private val repository: TenantProviderSettingsRepository,
    private val cipher: CredentialsCipher,
    private val objectMapper: ObjectMapper,
    private val resolver: MessagingProviderResolver,
    private val ownNumberGate: OwnWhatsAppNumberGate,
) {
    @Transactional(readOnly = true)
    fun overview(): ProviderSettingsResponse = ProviderSettingsResponse(email = emailView(), whatsapp = whatsAppView())

    @Transactional
    fun updateEmail(request: UpdateEmailProviderRequest): ProviderSettingsResponse {
        val credentials =
            ResendCredentials(
                apiKey = request.apiKey.trim(),
                from = request.from.trim(),
                fromName = request.fromName?.trim()?.takeIf { it.isNotBlank() },
            )
        upsert(
            channel = TenantProviderSettings.CHANNEL_EMAIL,
            provider = TenantProviderSettings.PROVIDER_RESEND,
            credentialsJson = objectMapper.writeValueAsString(credentials),
        )
        return overview()
    }

    @Transactional
    fun updateWhatsApp(request: UpdateWhatsAppProviderRequest): ProviderSettingsResponse {
        if (!ownNumberGate.ownNumberAllowed()) {
            throw ResponseStatusException(
                HttpStatus.PAYMENT_REQUIRED,
                "Your own WhatsApp number comes with the Organisation plan, the Organizer Pack or its monthly add-on",
            )
        }
        val credentials =
            MetaCloudCredentials(
                phoneNumberId = request.phoneNumberId.trim(),
                accessToken = request.accessToken.trim(),
                templateName = request.templateName?.trim()?.takeIf { it.isNotBlank() },
                templateLanguage = request.templateLanguage?.trim()?.takeIf { it.isNotBlank() } ?: "fr",
            )
        upsert(
            channel = TenantProviderSettings.CHANNEL_WHATSAPP,
            provider = TenantProviderSettings.PROVIDER_META_CLOUD,
            credentialsJson = objectMapper.writeValueAsString(credentials),
        )
        return overview()
    }

    @Transactional
    fun remove(channel: String): ProviderSettingsResponse {
        repository.findByChannel(channel)?.let { repository.delete(it) }
        return overview()
    }

    /**
     * Sends a short test message through the same resolution a real send uses
     * (tenant provider first, platform fallback), so the organizer verifies the
     * effective configuration end to end.
     */
    fun testSend(
        channel: String,
        recipient: String,
    ): TestSendResponse =
        when (channel) {
            TenantProviderSettings.CHANNEL_EMAIL -> {
                val resolved = resolver.email()
                runCatching {
                    resolved.sender.send(
                        resolved.from,
                        EmailMessage(
                            to = recipient,
                            toName = "",
                            subject = "Jikū — test message",
                            htmlBody = "<p>This is a test message confirming your email sending configuration works.</p>",
                        ),
                    )
                }.fold(
                    onSuccess = { TestSendResponse(delivered = true, usingTenantProvider = resolved.tenantOverride) },
                    onFailure = {
                        TestSendResponse(
                            delivered = false,
                            usingTenantProvider = resolved.tenantOverride,
                            error = it.message?.take(300),
                        )
                    },
                )
            }

            TenantProviderSettings.CHANNEL_WHATSAPP -> {
                val resolved = resolver.whatsApp()
                runCatching {
                    resolved.sender.send(
                        WhatsAppMessage(
                            to = recipient,
                            body = "Jikū — test message confirming your WhatsApp sending configuration works.",
                        ),
                    )
                }.fold(
                    onSuccess = { TestSendResponse(delivered = true, usingTenantProvider = resolved.tenantOverride) },
                    onFailure = {
                        TestSendResponse(
                            delivered = false,
                            usingTenantProvider = resolved.tenantOverride,
                            error = it.message?.take(300),
                        )
                    },
                )
            }

            else -> throw IllegalArgumentException("Unsupported channel: $channel")
        }

    private fun upsert(
        channel: String,
        provider: String,
        credentialsJson: String,
    ) {
        val encrypted = cipher.encrypt(credentialsJson)
        val existing = repository.findByChannel(channel)
        if (existing == null) {
            repository.save(TenantProviderSettings(channel = channel, provider = provider, credentials = encrypted))
        } else {
            existing.provider = provider
            existing.credentials = encrypted
            repository.save(existing)
        }
    }

    private fun emailView(): EmailProviderView {
        val row =
            repository.findByChannel(TenantProviderSettings.CHANNEL_EMAIL)
                ?: return EmailProviderView(configured = false)
        val credentials = objectMapper.readValue(cipher.decrypt(row.credentials), ResendCredentials::class.java)
        return EmailProviderView(
            configured = true,
            provider = row.provider,
            from = credentials.from,
            fromName = credentials.fromName,
            apiKeyMasked = maskSecret(credentials.apiKey),
        )
    }

    private fun whatsAppView(): WhatsAppProviderView {
        val row =
            repository.findByChannel(TenantProviderSettings.CHANNEL_WHATSAPP)
                ?: return WhatsAppProviderView(configured = false, allowed = ownNumberGate.ownNumberAllowed())
        val credentials = objectMapper.readValue(cipher.decrypt(row.credentials), MetaCloudCredentials::class.java)
        return WhatsAppProviderView(
            configured = true,
            provider = row.provider,
            phoneNumberId = credentials.phoneNumberId,
            accessTokenMasked = maskSecret(credentials.accessToken),
            templateName = credentials.templateName,
            templateLanguage = credentials.templateLanguage,
            allowed = ownNumberGate.ownNumberAllowed(),
        )
    }
}
