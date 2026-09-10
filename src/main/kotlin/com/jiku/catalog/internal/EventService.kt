package com.jiku.catalog.internal

import com.jiku.shared.EventCancelledEvent
import com.jiku.shared.EventDeletedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneId
import java.util.UUID

@Service
class EventService(
    private val events: EventRepository,
    private val ticketTypes: TicketTypeRepository,
    private val questions: EventQuestionRepository,
    private val occurrences: EventOccurrenceRepository,
    private val eventPublisher: ApplicationEventPublisher,
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
    fun list(): List<EventResponse> = events.findAllWithChannels().map { it.toResponse() }

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

    /**
     * Enregistre la règle de quorum (JIKU-94).
     *
     * `reachedAt` n'est jamais touché ici : c'est la valeur probante, écrite une
     * seule fois à la porte. Changer la règle après coup ne réécrit pas
     * l'histoire — si l'organisateur corrige son quorum en cours d'assemblée, la
     * date de première atteinte sous l'ancienne règle demeure, et c'est
     * volontaire.
     */
    @Transactional
    fun setQuorum(
        id: UUID,
        request: UpdateQuorumRequest,
    ): QuorumResponse {
        val event = load(id)
        when (request.mode) {
            QuorumMode.FRACTION ->
                if (request.numerator == null || request.denominator == null) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A fraction requires a numerator and a denominator")
                }
            QuorumMode.ABSOLUTE ->
                if (request.absolute == null) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An absolute quorum requires a number")
                }
            QuorumMode.NONE -> Unit
        }
        event.ensureQuorum().apply {
            mode = request.mode
            numerator = request.numerator
            denominator = request.denominator
            absolute = request.absolute
        }
        val saved = events.save(event).quorum
        return QuorumResponse(
            mode = saved?.mode?.name ?: QuorumMode.NONE.name,
            numerator = saved?.numerator,
            denominator = saved?.denominator,
            absolute = saved?.absolute,
            reachedAt = saved?.reachedAt,
        )
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

    /**
     * Cancels a published event. The [EventCancelledEvent] is consumed
     * synchronously by the ticketing module inside this same transaction, so the
     * status change and every ticket invalidation commit or roll back together;
     * guest notifications fan out only after the commit.
     */
    @Transactional
    fun cancel(
        id: UUID,
        notifyGuests: Boolean = true,
    ): EventResponse {
        val event = load(id)
        if (event.status != EventStatus.PUBLISHED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Only published events can be cancelled")
        }
        event.status = EventStatus.CANCELLED
        events.save(event)
        eventPublisher.publishEvent(
            EventCancelledEvent(requireNotNull(event.id), requireNotNull(event.tenantId), notifyGuests),
        )
        return event.toResponse()
    }

    private fun load(id: UUID): Event =
        events.findById(id).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        }

    /**
     * Deletes a draft or cancelled event. A published event must be cancelled
     * first (409). The [EventDeletedEvent] is consumed synchronously by the
     * ticketing, invitation and check-in modules inside this same transaction,
     * so the event and every row that referenced it disappear together — there
     * is no state where the event is gone but its guests or tickets remain.
     */
    @Transactional
    fun delete(id: UUID) {
        val event = load(id)
        if (event.status == EventStatus.PUBLISHED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cancel the event before deleting it",
            )
        }
        eventPublisher.publishEvent(EventDeletedEvent(requireNotNull(event.id), requireNotNull(event.tenantId)))
        ticketTypes.findByEventIdOrderByPositionAsc(id).let { ticketTypes.deleteAll(it) }
        questions.findByEventIdOrderByPositionAsc(id).let { questions.deleteAll(it) }
        occurrences.findByEventIdOrderByStartsAtAsc(id).let { occurrences.deleteAll(it) }
        events.delete(event)
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
