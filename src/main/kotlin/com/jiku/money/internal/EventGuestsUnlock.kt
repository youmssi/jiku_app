package com.jiku.money.internal

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * The single place a paid purchase raises an event's paid guests. Both
 * confirmation paths — the provider's server-to-server callback (JIKU-33) and an
 * admin confirming a manual payment (JIKU-41) — go through here, so they cannot
 * drift apart.
 */
@Service
class EventGuestsUnlock(
    private val usageRecords: UsageRecordRepository,
) {
    /**
     * Raises the event's paid guests to [guests] (never lowers them). Guests it
     * already sent for free stay counted for good against the tenant's
     * cumulative budget (JIKU-54) — paying does not reverse them.
     */
    fun unlock(
        eventId: UUID,
        guests: Long,
    ) {
        val record = usageRecords.findByEventId(eventId) ?: UsageRecord(eventId = eventId)
        record.paidGuests = maxOf(record.paidGuests, guests)
        record.updatedAt = Instant.now()
        usageRecords.save(record)
    }
}
