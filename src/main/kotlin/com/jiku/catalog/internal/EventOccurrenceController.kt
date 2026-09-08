package com.jiku.catalog.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Occurrences (dates) d'un événement multi-dates (ADR 103). La définition ne se
 * modifie qu'en brouillon : on ajoute/retire des dates tant que l'événement n'est
 * pas publié. La capacité par date est une jauge physique ; la facturation reste
 * à la racine.
 */
@RestController
@RequestMapping("/events/{eventId}/occurrences")
@PreAuthorize("hasRole('ORGANIZER')")
class EventOccurrenceController(
    private val occurrences: EventOccurrenceService,
) {
    @GetMapping
    fun list(
        @PathVariable eventId: UUID,
    ): List<EventOccurrenceResponse> = occurrences.list(eventId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: CreateEventOccurrenceRequest,
    ): EventOccurrenceResponse = occurrences.create(eventId, request)

    @DeleteMapping("/{occurrenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable eventId: UUID,
        @PathVariable occurrenceId: UUID,
    ) = occurrences.delete(eventId, occurrenceId)
}

data class CreateEventOccurrenceRequest(
    @field:NotNull val startsAt: Instant,
    val endsAt: Instant? = null,
    val capacity: Int? = null,
)

data class EventOccurrenceResponse(
    val id: UUID,
    val eventId: UUID,
    val startsAt: Instant,
    val endsAt: Instant?,
    val capacity: Int?,
)

@SpringService
class EventOccurrenceService(
    private val occurrences: EventOccurrenceRepository,
    private val events: EventRepository,
) {
    @Transactional(readOnly = true)
    fun list(eventId: UUID): List<EventOccurrenceResponse> = occurrences.findByEventIdOrderByStartsAtAsc(eventId).map { it.toResponse() }

    @Transactional
    fun create(
        eventId: UUID,
        request: CreateEventOccurrenceRequest,
    ): EventOccurrenceResponse {
        requireDraftEvent(eventId)
        val endsAt = request.endsAt
        if (endsAt != null && !endsAt.isAfter(request.startsAt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The occurrence end must be after its start")
        }
        if (request.capacity != null && request.capacity < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The occurrence capacity must be positive")
        }
        val occurrence =
            EventOccurrence(eventId = eventId, startsAt = request.startsAt).apply {
                this.endsAt = endsAt
                this.capacity = request.capacity
            }
        return occurrences.save(occurrence).toResponse()
    }

    @Transactional
    fun delete(
        eventId: UUID,
        occurrenceId: UUID,
    ) {
        requireDraftEvent(eventId)
        occurrences.delete(load(eventId, occurrenceId))
    }

    private fun load(
        eventId: UUID,
        occurrenceId: UUID,
    ): EventOccurrence =
        occurrences.findById(occurrenceId).orElse(null)?.takeIf { it.eventId == eventId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Occurrence not found")

    private fun requireDraftEvent(eventId: UUID) {
        val event =
            events.findById(eventId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        if (event.status != EventStatus.DRAFT) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Occurrences can only change while the event is a draft")
        }
    }

    private fun EventOccurrence.toResponse() =
        EventOccurrenceResponse(
            id = requireNotNull(id),
            eventId = eventId,
            startsAt = startsAt,
            endsAt = endsAt,
            capacity = capacity,
        )
}
