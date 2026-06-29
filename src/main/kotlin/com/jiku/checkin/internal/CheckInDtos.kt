package com.jiku.checkin.internal

import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

/** A QR scan: the ticket code decoded from the guest's ticket. */
data class ScanRequest(
    @field:NotBlank
    val ticketCode: String,
)

/** A manual, search-based check-in: the guest the validator selected. */
data class ManualCheckInRequest(
    val guestId: UUID,
)

/**
 * The result of a check-in attempt. [outcome] is one of the ticketing module's
 * [com.jiku.ticketing.CheckInOutcome] names; the validator UI switches on it to
 * render a glanceable success or failure state. For ALREADY_CHECKED_IN,
 * [checkedInAt]/[checkedInBy] describe the prior check-in.
 */
data class CheckInResponse(
    val outcome: String,
    val guestName: String?,
    val ticketCode: String?,
    val checkedInAt: Instant?,
    val checkedInBy: String?,
)

/** A guest matched by the manual search path, enriched with ticket state. */
data class GuestMatch(
    val guestId: UUID,
    val name: String,
    val email: String?,
    val phoneNumber: String?,
    val rsvpStatus: String,
    val ticketCode: String?,
    val ticketStatus: String?,
    val checkedInAt: Instant?,
    val checkedInBy: String?,
)

/** Real-time attendance counters for the event being checked in. */
data class AttendanceResponse(
    val checkedIn: Long,
    val confirmed: Long,
)
