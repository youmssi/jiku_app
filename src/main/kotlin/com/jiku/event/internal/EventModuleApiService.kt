package com.jiku.event.internal

import com.jiku.event.EventInfo
import com.jiku.event.EventModuleApi
import com.jiku.event.InvitationChannel
import com.jiku.event.RetentionCandidate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class EventModuleApiService(
    private val events: EventRepository,
    private val eventService: EventService,
) : EventModuleApi {
    @Transactional(readOnly = true)
    override fun findEvent(eventId: UUID): EventInfo? = events.findById(eventId).map { it.toEventInfo() }.orElse(null)

    @Transactional(readOnly = true)
    override fun eventsPastRetention(cutoff: Instant): List<RetentionCandidate> =
        events.findEventsPastRetention(cutoff).map {
            RetentionCandidate(eventId = UUID.fromString(it[0].toString()), tenantId = it[1].toString())
        }

    @Transactional
    override fun reserveAttendanceSlot(eventId: UUID): Boolean {
        val event = events.findById(eventId).orElse(null) ?: return false
        val limit =
            event.maxCapacity?.let { capacity ->
                capacity + if (event.settings.overbookingAllowed) (event.settings.maxOverbookingCount ?: 0) else 0
            } ?: Int.MAX_VALUE
        return events.reserveSlot(eventId, limit) == 1
    }

    @Transactional
    override fun releaseAttendanceSlot(eventId: UUID) {
        events.releaseSlot(eventId)
    }

    @Transactional
    override fun createDraftEvent(
        name: String,
        timezone: String,
        startDateTime: Instant?,
        invitationChannels: Set<InvitationChannel>,
    ): UUID =
        eventService
            .create(
                CreateEventRequest(
                    name = name,
                    timezone = timezone,
                    startDateTime = startDateTime,
                    invitationChannels = invitationChannels,
                ),
            ).id
}
