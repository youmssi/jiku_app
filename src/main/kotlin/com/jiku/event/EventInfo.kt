package com.jiku.event

import java.time.Instant
import java.util.UUID

/**
 * Read-only view of an event shared across module boundaries. Times are UTC
 * instants; [timezone] is the event's IANA zone for presentation.
 */
data class EventInfo(
    val id: UUID,
    val tenantId: String,
    val name: String,
    val status: String,
    val startDateTime: Instant?,
    val endDateTime: Instant?,
    val timezone: String,
    val location: String?,
)

/** An event (with its owning tenant) whose data is due for retention anonymization. */
data class RetentionCandidate(
    val eventId: UUID,
    val tenantId: String,
)
