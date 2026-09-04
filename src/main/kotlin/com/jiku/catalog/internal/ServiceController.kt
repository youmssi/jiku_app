package com.jiku.catalog.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Services d'un organisateur (JIKU-87) : ce qu'on réserve (coupe, coloration…),
 * avec ses exigences en ressources. Le partage d'un lien de réservation client
 * s'ajoute dans la foulée.
 */
@RestController
@RequestMapping("/services")
@PreAuthorize("hasRole('ORGANIZER')")
class ServiceController(
    private val services: ServiceAdminService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: ServiceCreateRequest,
    ): ServiceResponse = services.create(request.name, request.timezone)

    @GetMapping
    fun list(): List<ServiceResponse> = services.list()

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ServiceResponse = services.get(id)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ServiceUpdateRequest,
    ): ServiceResponse = services.updateName(id, request.name)

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
}
