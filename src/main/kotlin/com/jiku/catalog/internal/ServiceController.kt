package com.jiku.catalog.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Services d'un organisateur (JIKU-87) : ce qu'on réserve (coupe, coloration…),
 * avec ses exigences en ressources et le lien de réservation partageable au
 * client.
 */
@RestController
@RequestMapping("/services")
@PreAuthorize("hasRole('ORGANIZER')")
class ServiceController(
    private val services: ServiceAdminService,
    private val linkTokens: ServiceLinkTokenService,
    private val serviceLinkCodes: ServiceLinkCodeService,
    private val config: ServiceConfigService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: ServiceCreateRequest,
    ): ServiceResponse = services.create(request)

    @GetMapping
    fun list(): List<ServiceResponse> = services.list()

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ServiceResponse = services.get(id)

    /** Lien signé partageable au client, sans compte (JIKU-87) + son code court. */
    @GetMapping("/{id}/booking-link")
    fun bookingLink(
        @PathVariable id: UUID,
    ): BookingLinkResponse {
        services.get(id)
        val tenantId = TenantContext.get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant")
        val link = serviceLinkCodes.forService(id, tenantId)
        return BookingLinkResponse(token = linkTokens.issue(id, tenantId), shortCode = link.code)
    }

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ServiceUpdateRequest,
    ): ServiceResponse = services.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
    ) = services.delete(id)

    @GetMapping("/{id}/requirements")
    fun requirements(
        @PathVariable id: UUID,
    ): List<ServiceRequirementResponse> = services.listRequirements(id)

    @PostMapping("/{id}/requirements")
    @ResponseStatus(HttpStatus.CREATED)
    fun addRequirement(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ServiceRequirementRequest,
    ): ServiceRequirementResponse = services.addRequirement(id, request.type, request.quantity)

    @DeleteMapping("/{id}/requirements/{requirementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeRequirement(
        @PathVariable id: UUID,
        @PathVariable requirementId: UUID,
    ) {
        services.removeRequirement(id, requirementId)
    }

    /** Options effectives du service, renseignées ou par défaut (JIKU-89). */
    @GetMapping("/{id}/configuration")
    fun configuration(
        @PathVariable id: UUID,
    ): EffectiveServiceConfig {
        services.get(id)
        return config.effective(id)
    }

    /** Met à jour partiellement les options du service (JIKU-86/89). */
    @PutMapping("/{id}/configuration")
    fun updateConfiguration(
        @PathVariable id: UUID,
        @RequestBody update: ServiceConfigUpdate,
    ): EffectiveServiceConfig {
        services.get(id)
        return config.update(id, update)
    }
}
