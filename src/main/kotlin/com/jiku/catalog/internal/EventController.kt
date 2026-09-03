package com.jiku.catalog.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/events")
@PreAuthorize("hasRole('ORGANIZER')")
class EventController(
    private val eventService: EventService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateEventRequest,
    ): EventResponse = eventService.create(request)

    @GetMapping
    fun list(): List<EventResponse> = eventService.list()

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): EventResponse = eventService.get(id)

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateEventRequest,
    ): EventResponse = eventService.update(id, request)

    /**
     * Règle de quorum de l'événement (JIKU-94). Séparée de la mise à jour
     * générale : c'est une règle statutaire, saisie une fois, pas un réglage
     * qu'on ajuste en modifiant le lieu ou l'horaire.
     */
    @PutMapping("/{id}/quorum")
    fun setQuorum(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateQuorumRequest,
    ): QuorumResponse = eventService.setQuorum(id, request)

    @PostMapping("/{id}/publish")
    fun publish(
        @PathVariable id: UUID,
    ): EventResponse = eventService.publish(id)

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
    ): EventResponse = eventService.cancel(id)
}
