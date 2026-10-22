package com.jiku.money.internal

import com.jiku.catalog.EventModuleApi
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Whether an event is on its day: within a day of its start, before or after.
 * Sending never stops then under Pro Organisateur (ADR 105), and an event that
 * pays per guest gets [BillingProperties.EventDayGrace] (JIKU-270).
 */
@Component
class EventDay(
    private val events: EventModuleApi,
) {
    fun isEventDay(
        eventId: UUID,
        now: Instant = Instant.now(),
    ): Boolean {
        val start = events.findEvent(eventId)?.startDateTime ?: return false
        return Duration.between(now, start).abs() <= WINDOW
    }

    private companion object {
        val WINDOW: Duration = Duration.ofHours(24)
    }
}
