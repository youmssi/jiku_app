package com.jiku.messaging.internal

import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant

/** What Meta answered for one template Jikū asked it to create. */
data class TemplatePublication(
    val name: String,
    val language: String,
    val created: Boolean,
    val error: String? = null,
)

/**
 * Creates the dedicated WhatsApp templates (JIKU-210) in a WhatsApp Business
 * Account: every [WhatsAppTemplateKind] in French and English, as UTILITY,
 * from `whatsapp-templates/meta/dedicated.json`. Meta then reviews each one;
 * one it refuses, or one that already exists, is reported and does not stop
 * the others.
 */
@Component
class WhatsAppTemplatePublisher(
    private val meta: MetaOnboardingClient,
    private val properties: WhatsAppProperties,
    private val objectMapper: ObjectMapper,
) {
    fun publishDedicated(
        wabaId: String,
        token: String,
    ): List<TemplatePublication> {
        val definitions =
            ClassPathResource(DEFINITIONS).inputStream.use { objectMapper.readValue<Map<String, Map<String, Any>>>(it) }
        val imageHandle by lazy {
            runCatching {
                meta.uploadExample(token, EXAMPLE_IMAGE, "image/png", ClassPathResource(EXAMPLE_IMAGE_PATH).contentAsByteArray)
            }
        }
        return WhatsAppTemplateKind.entries.flatMap { kind ->
            val definition = requireNotNull(definitions[kind.key]) { "No definition for WhatsApp template ${kind.key}" }
            LANGUAGES.map { language ->
                val name = properties.meta.templatePrefix + kind.key
                val components = definition[language] ?: error("No $language text for WhatsApp template ${kind.key}")
                publish(name, language, components, if (definition["image"] == true) imageHandle else null, wabaId, token)
            }
        }
    }

    private fun publish(
        name: String,
        language: String,
        components: Any,
        imageHandle: Result<String>?,
        wabaId: String,
        token: String,
    ): TemplatePublication {
        val handle =
            imageHandle?.getOrElse {
                return TemplatePublication(
                    name,
                    language,
                    false,
                    "Example image upload failed: ${it.message}",
                )
            }
        val json = objectMapper.writeValueAsString(components).let { if (handle == null) it else it.replace(IMAGE_HANDLE_MARKER, handle) }
        val template =
            mapOf(
                "name" to name,
                "language" to language,
                "category" to "UTILITY",
                "components" to objectMapper.readValue<List<Map<String, Any>>>(json),
            )
        return runCatching { meta.createTemplate(wabaId, token, template) }
            .fold({ TemplatePublication(name, language, true) }, { TemplatePublication(name, language, false, it.message) })
    }

    private companion object {
        const val DEFINITIONS = "whatsapp-templates/meta/dedicated.json"
        const val EXAMPLE_IMAGE = "ticket-example.png"
        const val EXAMPLE_IMAGE_PATH = "whatsapp-templates/meta/$EXAMPLE_IMAGE"
        const val IMAGE_HANDLE_MARKER = "{{EXAMPLE_IMAGE_HANDLE}}"
        val LANGUAGES = listOf("fr", "en")
    }
}

/** What Meta last said about a template, for the platform team (JIKU-209, JIKU-210). */
data class TemplateStateView(
    val wabaId: String,
    val name: String,
    val language: String,
    val status: String?,
    val quality: String?,
    val category: String?,
    val reason: String?,
    val blockedUntil: Instant?,
    val updatedAt: Instant,
)

/**
 * The platform team's view of Jikū's WhatsApp templates: `POST` creates the
 * dedicated templates in the platform account (once, then after a wording
 * change), `GET` lists what Meta reported about every template it knows.
 */
@RestController
@RequestMapping("/admin/whatsapp/templates")
class AdminWhatsAppTemplateController(
    private val publisher: WhatsAppTemplatePublisher,
    private val states: WhatsAppTemplateStateRepository,
    private val properties: WhatsAppProperties,
) {
    @PostMapping
    fun publish(): List<TemplatePublication> {
        val meta = properties.meta
        if (meta.businessAccountId.isBlank() || meta.accessToken.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Set WHATSAPP_META_BUSINESS_ACCOUNT_ID and WHATSAPP_META_ACCESS_TOKEN first",
            )
        }
        return publisher.publishDedicated(meta.businessAccountId, meta.accessToken)
    }

    @GetMapping
    fun list(): List<TemplateStateView> =
        states.findAll().sortedWith(compareBy({ it.key.name }, { it.key.language })).map {
            TemplateStateView(
                wabaId = it.key.wabaId,
                name = it.key.name,
                language = it.key.language,
                status = it.status,
                quality = it.quality,
                category = it.category,
                reason = it.reason,
                blockedUntil = it.blockedUntil,
                updatedAt = it.updatedAt,
            )
        }
}
