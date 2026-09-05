package com.jiku.messaging.internal

import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Gabarits e-mail/WhatsApp du tenant (JIKU-91), adressés à ses clients. Repli sur
 * le défaut du produit ; un gabarit invalide n'empêche jamais un envoi.
 */
@RestController
@RequestMapping("/settings/templates")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class TemplatesController(
    private val personalization: TenantPersonalizationService,
) {
    @GetMapping
    fun list(): List<TemplateSummary> = personalization.templateSummaries()

    @GetMapping("/{name}")
    fun detail(
        @PathVariable name: String,
    ): TemplateDetail = personalization.templateDetail(name)

    @PutMapping("/{name}")
    fun save(
        @PathVariable name: String,
        @RequestBody update: TemplateUpdate,
    ): TemplateDetail = personalization.saveTemplate(name, update)

    @PostMapping("/{name}/preview")
    fun preview(
        @PathVariable name: String,
        @RequestBody request: TemplatePreviewRequest,
    ): TemplatePreviewResponse {
        if (ClientTemplates.definition(name) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown template: $name")
        }
        return personalization.preview(name, request)
    }
}
