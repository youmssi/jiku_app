package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.security.SecureRandom

/**
 * Connects an organization's own WhatsApp number through Meta's Embedded
 * Signup (ADR 105). The web opens Meta's window with [config]; the organizer
 * picks or creates the business account and number, and the window hands back
 * a code with the account and number ids. [complete] then turns the code into
 * the organization's token, subscribes Jikū to the account's messages,
 * registers the number, creates Jikū's templates in the account and saves it
 * all as the organization's WhatsApp provider. From then on its guests hear
 * from its number and Meta bills it for the messages.
 *
 * A template Meta refuses (one that already exists, say) does not undo the
 * connection: it is logged, and the number still works inside the 24-hour
 * window while the organizer sorts it out in Meta.
 */
@Service
class EmbeddedSignupService(
    private val properties: WhatsAppProperties,
    private val meta: MetaOnboardingClient,
    private val settings: TenantProviderSettingsService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(EmbeddedSignupService::class.java)
    private val random = SecureRandom()

    fun config(): EmbeddedSignupConfig {
        val meta = properties.meta
        if (!enabled()) return EmbeddedSignupConfig(enabled = false)
        return EmbeddedSignupConfig(
            enabled = true,
            appId = meta.appId,
            configId = meta.embeddedSignupConfigId,
            graphVersion = meta.baseUrl.trimEnd('/').substringAfterLast('/'),
        )
    }

    fun complete(request: CompleteEmbeddedSignupRequest): ProviderSettingsResponse {
        if (!enabled()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Embedded Signup is not configured")
        settings.requireOwnNumberAllowed()
        val wabaId = request.wabaId.trim()
        val phoneNumberId = request.phoneNumberId.trim()
        val credentials =
            try {
                val token = meta.exchangeCode(request.code.trim())
                meta.subscribeApp(wabaId, token)
                val pin = pin()
                meta.register(phoneNumberId, token, pin)
                createTemplates(wabaId, token)
                val number = meta.phoneNumber(phoneNumberId, token)
                MetaCloudCredentials(
                    phoneNumberId = phoneNumberId,
                    accessToken = token,
                    templateName = properties.meta.templateName.takeIf { it.isNotBlank() },
                    templateLanguage = properties.meta.templateLanguage,
                    buttonsTemplateName = properties.meta.buttonsTemplateName.takeIf { it.isNotBlank() },
                    imageTemplateName = properties.meta.imageTemplateName.takeIf { it.isNotBlank() },
                    wabaId = wabaId,
                    displayPhoneNumber = number.displayPhoneNumber,
                    verifiedName = number.verifiedName,
                    pin = pin,
                )
            } catch (e: WhatsAppOnboardingException) {
                log.warn("Embedded Signup could not finish for number {}", phoneNumberId, e)
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message, e)
            }
        settings.saveWhatsApp(credentials, TenantProviderSettings.PROVIDER_META_EMBEDDED)
        return settings.overview()
    }

    private fun enabled(): Boolean =
        properties.meta.appId.isNotBlank() &&
            properties.meta.embeddedSignupConfigId.isNotBlank() &&
            properties.meta.appSecret.isNotBlank()

    /** Jikū's templates, under the names the platform uses, created in the organization's account. */
    private fun createTemplates(
        wabaId: String,
        token: String,
    ) {
        val names =
            mapOf(
                "text" to properties.meta.templateName,
                "buttons" to properties.meta.buttonsTemplateName,
                "image" to properties.meta.imageTemplateName,
            ).filterValues { it.isNotBlank() }
        if (names.isEmpty()) return
        val definitions =
            ClassPathResource(TEMPLATES).inputStream.use { objectMapper.readValue<Map<String, List<Map<String, Any>>>>(it) }
        val imageHandle =
            if ("image" in names) {
                runCatching {
                    meta.uploadExample(token, EXAMPLE_IMAGE, "image/png", ClassPathResource(EXAMPLE_IMAGE_PATH).contentAsByteArray)
                }.onFailure { log.warn("The ticket template's example image could not be uploaded", it) }.getOrNull()
            } else {
                null
            }
        for ((kind, name) in names) {
            if (kind == "image" && imageHandle == null) continue
            val components = definitions[kind] ?: continue
            val template =
                mapOf(
                    "name" to name,
                    "language" to properties.meta.templateLanguage,
                    "category" to "UTILITY",
                    "components" to withHandle(components, imageHandle),
                )
            runCatching { meta.createTemplate(wabaId, token, template) }
                .onFailure { log.warn("Template {} could not be created in WhatsApp account {}", name, wabaId, it) }
        }
    }

    private fun withHandle(
        components: List<Map<String, Any>>,
        handle: String?,
    ): List<Map<String, Any>> {
        if (handle == null) return components
        val json = objectMapper.writeValueAsString(components).replace(IMAGE_HANDLE_MARKER, handle)
        return objectMapper.readValue(json)
    }

    private fun pin(): String = (0 until PIN_LENGTH).joinToString("") { random.nextInt(10).toString() }

    private companion object {
        const val TEMPLATES = "whatsapp-templates/meta/templates.json"
        const val EXAMPLE_IMAGE = "ticket-example.png"
        const val EXAMPLE_IMAGE_PATH = "whatsapp-templates/meta/$EXAMPLE_IMAGE"
        const val IMAGE_HANDLE_MARKER = "{{EXAMPLE_IMAGE_HANDLE}}"
        const val PIN_LENGTH = 6
    }
}
