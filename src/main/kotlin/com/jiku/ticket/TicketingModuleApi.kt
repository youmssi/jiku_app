package com.jiku.ticket

import java.time.Instant
import java.util.UUID

/**
 * The ticketing module's public API. The RSVP flow issues a ticket on
 * confirmation and cancels it on decline; check-in resolves a ticket by its code
 * (or its guest) and atomically transitions it to checked-in. Reads/writes are
 * tenant-scoped by the persistence-layer filter.
 */
interface TicketingModuleApi {
    fun issueTicket(
        eventId: UUID,
        guestId: UUID,
    ): TicketInfo

    fun cancelByGuest(guestId: UUID)

    fun findByGuest(guestId: UUID): TicketInfo?

    fun findByCode(ticketCode: String): TicketInfo?

    /**
     * Attempts to check in the ticket identified by [ticketCode], attributing the
     * action to [checkedInBy]. The transition is atomic; see [CheckInResult].
     */
    fun checkInByCode(
        ticketCode: String,
        checkedInBy: String,
    ): CheckInResult

    /**
     * Attempts to check in the ticket belonging to [guestId] (the manual,
     * search-based path), attributing the action to [checkedInBy].
     */
    fun checkInByGuest(
        guestId: UUID,
        checkedInBy: String,
    ): CheckInResult

    /**
     * Offline-originated check-in carrying the moment it was scanned on the
     * validator's device ([scannedAt]). Conflicts between two offline validators
     * are resolved first-timestamp-wins: the earliest scan owns the authoritative
     * record. Returns [CheckInOutcome.CHECKED_IN] when this scan owns the record
     * (it was first, or it superseded a later one) and [CheckInOutcome.ALREADY_CHECKED_IN]
     * when an earlier scan already stands.
     */
    fun syncCheckInByCode(
        ticketCode: String,
        checkedInBy: String,
        scannedAt: Instant,
    ): CheckInResult

    /** Real-time attendance figures for an event, scoped to the current tenant. */
    fun attendanceStats(eventId: UUID): AttendanceStats

    /** All tickets for an event (for pre-syncing a validator's offline roster). */
    fun findTicketsByEvent(eventId: UUID): List<TicketInfo>

    /** Checked-in counts grouped by the label that performed each check-in. */
    fun checkInCountsByLabel(eventId: UUID): Map<String, Long>
}

data class TicketInfo(
    val id: UUID,
    val eventId: UUID,
    val guestId: UUID,
    val ticketCode: String,
    val status: String,
    val issuedAt: Instant,
    val checkedInAt: Instant? = null,
    val checkedInBy: String? = null,
) {
    companion object {
        /** [status] of a ticket already used at the entrance, shared so consumers avoid magic strings. */
        const val STATUS_CHECKED_IN = "CHECKED_IN"

        /** [status] of a ticket cancelled by a decline or a transfer; it no longer validates. */
        const val STATUS_CANCELLED = "CANCELLED"
    }
}

/**
 * Outcome of a check-in attempt, carrying enough detail for the caller to render a
 * clear validator-facing message without a second lookup.
 *
 * - [CheckInOutcome.CHECKED_IN] — first successful check-in; [checkedInAt]/[checkedInBy]
 *   describe the action just performed.
 * - [CheckInOutcome.ALREADY_CHECKED_IN] — the ticket was already checked in;
 *   [checkedInAt]/[checkedInBy] describe the prior check-in.
 * - [CheckInOutcome.CANCELLED] — the ticket belongs to a guest who declined.
 * - [CheckInOutcome.NOT_FOUND] — no such ticket in the current tenant context.
 */
data class CheckInResult(
    val outcome: CheckInOutcome,
    val ticket: TicketInfo? = null,
    val checkedInAt: Instant? = null,
    val checkedInBy: String? = null,
)

enum class CheckInOutcome {
    CHECKED_IN,
    ALREADY_CHECKED_IN,
    CANCELLED,
    NOT_FOUND,
}

data class AttendanceStats(
    val checkedIn: Long,
    val confirmed: Long,
)
