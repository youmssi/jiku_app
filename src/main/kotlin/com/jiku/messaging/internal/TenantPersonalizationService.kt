package com.jiku.messaging.internal

import com.jiku.shared.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Personnalisation du tenant (JIKU-91) : termes du produit et gabarits client
 * e-mail/WhatsApp, avec repli sur les défauts du build. Les services ne stockent
 * que la surcharge ; l'absence de ligne garde le comportement par défaut.
 */
@Service
class TenantPersonalizationService(
    private val templates: TenantTemplateRepository,
    private val vocabulary: TenantVocabularyRepository,
    private val resolver: TenantTemplateResolver,
    private val defaults: ClientTemplateDefaults,
    private val catalog: MessageCatalog,
    private val emailRenderer: EmailTemplateRenderer,
) {
    // ─── Vocabulaire ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun vocabulary(): List<VocabularyEntry> {
        val rows = vocabulary.findAllByOrderByKeyAsc().associateBy { it.key }
        return VocabularyCatalog.terms.map { term ->
            val override = rows[term.key]
            VocabularyEntry(
                key = term.key,
                label = term.label,
                defaultValue = term.defaultValue,
                value = override?.value ?: term.defaultValue,
                overridden = override != null,
            )
        }
    }

    @Transactional
    fun saveVocabulary(updates: List<VocabularyUpdate>): List<VocabularyEntry> {
        requireNotNull(TenantContext.get()) { "Vocabulary updates require an authenticated tenant" }
        updates.forEach { update ->
            val term =
                VocabularyCatalog.term(update.key)
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown vocabulary key: ${update.key}")
            val value = update.value?.trim().orEmpty()
            val existing = vocabulary.findByKey(term.key)
            when {
                value.isEmpty() -> existing?.let { vocabulary.delete(it) }
                existing != null -> {
                    existing.value = value
                    existing.updatedAt = Instant.now()
                }
                else -> vocabulary.save(TenantVocabulary(key = term.key, value = value))
            }
        }
        return vocabulary()
    }

    // ─── Gabarits ──────────────────────────────────────────────────────────────

    fun templateSummaries(): List<TemplateSummary> =
        ClientTemplates.definitions.map {
            TemplateSummary(name = it.name, label = it.label, channels = it.channels)
        }

    @Transactional(readOnly = true)
    fun templateDetail(name: String): TemplateDetail {
        val definition =
            ClientTemplates.definition(name)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown template: $name")
        val rows = templates.findByNameOrderByChannelAsc(name).associateBy { it.channel }
        val language = tenantLanguage()
        val channels =
            definition.channels.mapNotNull { channel ->
                val defaultBody = defaults.load(name, channel, language) ?: return@mapNotNull null
                val override = rows[channel]
                TemplateChannelView(
                    channel = channel,
                    defaultBody = defaultBody,
                    body = override?.body ?: defaultBody,
                    isOverride = override != null,
                    active = override?.active ?: true,
                )
            }
        val sampleVariables = definition.channels.firstNotNullOfOrNull { definition.variables(it) }.orEmpty()
        return TemplateDetail(
            name = definition.name,
            label = definition.label,
            channels = channels,
            variables =
                sampleVariables.map {
                    TemplateVariable(it.name, it.label, it.sample, it.required)
                },
        )
    }

    @Transactional
    fun saveTemplate(
        name: String,
        update: TemplateUpdate,
    ): TemplateDetail {
        val definition =
            ClientTemplates.definition(name)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown template: $name")
        val body = update.body.trim()
        if (body.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A template body cannot be empty")
        }
        if (definition.defaultFile(update.channel) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel ${update.channel} is not available for $name")
        }
        val existing = templates.findByNameAndChannel(name, update.channel)
        if (existing != null) {
            existing.body = body
            existing.active = update.active
            existing.updatedAt = Instant.now()
        } else {
            templates.save(
                TenantTemplate(name = name, channel = update.channel, body = body).apply {
                    active = update.active
                },
            )
        }
        return templateDetail(name)
    }

    @Transactional(readOnly = true)
    fun preview(
        name: String,
        request: TemplatePreviewRequest,
    ): TemplatePreviewResponse {
        val definition =
            ClientTemplates.definition(name)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown template: $name")
        if (definition.defaultFile(request.channel) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel ${request.channel} is not available for $name")
        }
        val language = tenantLanguage()
        val defaultBody = defaults.load(name, request.channel, language) ?: ""
        val override = templates.findByNameAndChannel(name, request.channel)?.takeIf { it.active }?.body
        val body = request.body?.takeIf { it.isNotBlank() } ?: override ?: defaultBody
        val values = previewValues(request.channel, language)
        return TemplatePreviewResponse(body = resolver.substitute(body, values))
    }

    private fun tenantLanguage(): String = catalog.language(TenantContext.get())

    private fun previewValues(
        channel: String,
        language: String,
    ): Map<String, String> {
        val samples =
            ClientTemplates.definitions
                .flatMap { it.variables(channel) }
                .associate { it.name to it.sample }
        return if (channel == ClientTemplates.CHANNEL_EMAIL) {
            samples +
                mapOf(
                    "eventDetails" to emailRenderer.sampleEventDetails(language),
                    "logoBlock" to "",
                )
        } else {
            samples
        }
    }
}
