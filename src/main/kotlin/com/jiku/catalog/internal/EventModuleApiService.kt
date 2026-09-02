package com.jiku.catalog.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
import com.jiku.catalog.QuorumInfo
import com.jiku.catalog.RetentionCandidate
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

    @Transactional(readOnly = true)
    override fun quorum(
        eventId: UUID,
        totalGuests: Long,
        checkedIn: Long,
    ): QuorumInfo? {
        val event = events.findById(eventId).orElse(null) ?: return null
        val quorum = event.quorum ?: return null
        if (!quorum.isConfigured()) {
            return null
        }
        val required = quorum.requiredFor(totalGuests) ?: return null
        return QuorumInfo(
            required = required,
            current = checkedIn,
            reached = checkedIn >= required,
            reachedAt = quorum.reachedAt,
        )
    }

    @Transactional
    override fun markQuorumReached(eventId: UUID) {
        events.markQuorumReached(eventId, Instant.now())
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
