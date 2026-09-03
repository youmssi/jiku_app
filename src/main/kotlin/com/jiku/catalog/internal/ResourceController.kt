package com.jiku.catalog.internal

import com.jiku.catalog.ResourceAvailabilityView
import com.jiku.catalog.ResourceModuleApi
import com.jiku.catalog.ResourceUnavailabilityView
import com.jiku.catalog.ResourceView
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * Ressources d'un organisateur (JIKU-84) : une ressource (personne, lieu,
 * équipement), ses horaires hebdomadaires, ses indisponibilités, et la question
 * « libre sur cette case ? » qui alimentera le moteur de créneaux (JIKU-85).
 */
@RestController
@RequestMapping("/resources")
@PreAuthorize("hasRole('ORGANIZER')")
class ResourceController(
    private val resources: ResourceModuleApi,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: ResourceCreateRequest,
    ): ResourceView = resources.createResource(request.name, request.type, request.timezone)

    @GetMapping
    fun list(): List<ResourceView> = resources.listResources()

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResourceView? = resources.findResource(id)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ResourceUpdateRequest,
    ): ResourceView = resources.updateResource(id, request.name, request.active)

    @GetMapping("/{id}/availability")
    fun availability(
        @PathVariable id: UUID,
    ): List<ResourceAvailabilityView> = resources.listAvailability(id)

    @PostMapping("/{id}/availability")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAvailability(
        @PathVariable id: UUID,
        @Valid @RequestBody request: AvailabilityRequest,
    ): ResourceAvailabilityView = resources.addAvailability(id, request.dayOfWeek, request.start, request.end)

    @DeleteMapping("/{id}/availability/{availabilityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeAvailability(
        @PathVariable id: UUID,
        @PathVariable availabilityId: UUID,
    ) {
        resources.removeAvailability(id, availabilityId)
    }

    @GetMapping("/{id}/unavailability")
    fun unavailability(
        @PathVariable id: UUID,
    ): List<ResourceUnavailabilityView> = resources.listUnavailability(id)

    @PostMapping("/{id}/unavailability")
    @ResponseStatus(HttpStatus.CREATED)
    fun addUnavailability(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UnavailabilityRequest,
    ): ResourceUnavailabilityView = resources.addUnavailability(id, request.startsAt, request.endsAt, request.reason)

    @DeleteMapping("/{id}/unavailability/{unavailabilityId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeUnavailability(
        @PathVariable id: UUID,
        @PathVariable unavailabilityId: UUID,
    ) {
        resources.removeUnavailability(id, unavailabilityId)
    }

    @GetMapping("/{id}/free")
    fun free(
        @PathVariable id: UUID,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): FreeSlotResponse = FreeSlotResponse(resources.isSlotFree(id, from, to))
}
