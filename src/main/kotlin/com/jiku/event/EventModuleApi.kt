package com.jiku.event

import java.time.Instant
import java.util.UUID

/**
 * The event module's public API. Other modules (invitation, ticketing, checkin)
 * read events through this interface only. Reads are scoped to the current tenant
 * by the persistence-layer tenant filter, so an event is only visible within its
 * own tenant's context.
 *
 * Kept deliberately minimal and stable — many modules depend on it.
 */
interface EventModuleApi {
    fun findEvent(eventId: UUID): EventInfo?

    /**
     * Events across all tenants whose date is before [cutoff] — the retention job's
     * (JIKU-37) worklist. Intentionally cross-tenant: it is a platform maintenance
     * task, and the caller binds each event's own tenant before touching its data.
     */
    fun eventsPastRetention(cutoff: Instant): List<RetentionCandidate>

    /**
     * Atomically reserves one attendance slot, honoring capacity and overbooking.
     * Returns true if a slot was taken, false if the event is full.
     */
    fun reserveAttendanceSlot(eventId: UUID): Boolean

    /** Releases a previously reserved attendance slot (e.g. a guest who declines). */
    fun releaseAttendanceSlot(eventId: UUID)
}
