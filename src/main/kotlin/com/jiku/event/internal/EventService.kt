package com.jiku.event.internal

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneId
import java.util.UUID

@Service
class EventService(
    private val events: EventRepository,
) {
    @Transactional
    fun create(request: CreateEventRequest): EventResponse {
        validateTimezone(request.timezone)
        val event =
            Event(
                name = request.name,
                description = request.description,
                startDateTime = request.startDateTime,
                endDateTime = request.endDateTime,
                timezone = request.timezone,
                location = request.location,
            )
        event.settings = request.settings.toEmbeddable()
        event.maxCapacity = request.maxCapacity
        event.invitationChannels = request.invitationChannels.toMutableSet()
        events.save(event)
        return event.toResponse()
    }

    @Transactional(readOnly = true)
    fun list(): List<EventResponse> = events.findAll().map { it.toResponse() }

    @Transactional(readOnly = true)
    fun get(id: UUID): EventResponse = load(id).toResponse()

    @Transactional
    fun update(
        id: UUID,
        request: UpdateEventRequest,
    ): EventResponse {
        val event = load(id)
        requireDraft(event)
        validateTimezone(request.timezone)
        event.name = request.name
        event.description = request.description
        event.startDateTime = request.startDateTime
        event.endDateTime = request.endDateTime
        event.timezone = request.timezone
        event.location = request.location
        event.settings = request.settings.toEmbeddable()
        event.maxCapacity = request.maxCapacity
        event.invitationChannels = request.invitationChannels.toMutableSet()
        events.save(event)
        return event.toResponse()
    }

    @Transactional
    fun publish(id: UUID): EventResponse {
        val event = load(id)
        requireDraft(event)
        val missing =
            buildList {
                if (event.name.isBlank()) add("a name")
                if (event.startDateTime == null) add("a start date and time")
                if (event.invitationChannels.isEmpty()) add("at least one invitation channel")
            }
        if (missing.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Cannot publish: the event needs ${missing.joinToString(", ")}.",
            )
        }
        event.status = EventStatus.PUBLISHED
        events.save(event)
        return event.toResponse()
    }

    private fun load(id: UUID): Event =
        events.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        }

    private fun requireDraft(event: Event) {
        if (event.status != EventStatus.DRAFT) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Only draft events can be modified")
        }
    }

    private fun validateTimezone(timezone: String) {
        if (timezone !in ZoneId.getAvailableZoneIds()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown timezone: $timezone")
        }
    }
}
