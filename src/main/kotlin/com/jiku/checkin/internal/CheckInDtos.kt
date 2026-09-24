package com.jiku.checkin.internal

import com.jiku.catalog.OperatorAction
import com.jiku.ticket.TicketPaymentStatus
import jakarta.validation.Valid
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
 * [com.jiku.ticket.CheckInOutcome] names, or the check-in-level
 * [CheckInService.EVENT_CANCELLED] when the event itself was cancelled; the
 * validator UI switches on it to render a glanceable success or failure state.
 * For ALREADY_CHECKED_IN, [checkedInAt]/[checkedInBy] describe the prior check-in.
 */
data class CheckInResponse(
    val outcome: String,
    val guestName: String?,
    val ticketCode: String?,
    val checkedInAt: Instant?,
    val checkedInBy: String?,
    /**
     * Catégorie d'accès du billet (JIKU-93), null si l'événement n'en définit
     * pas. Le portier la lit à deux mètres : c'est elle qui décide si la
     * personne entre en salle, au carré VIP ou sur scène.
     */
    val ticketTypeLabel: String? = null,
    val ticketTypeColor: String? = null,
    /** On PAYMENT_DUE, what the guest owes the organization before entering (JIKU-110). */
    val amountDueMinor: Long? = null,
    val amountDueCurrency: String? = null,
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
    /** Catégorie d'accès du billet (JIKU-93), si l'événement en définit. */
    val ticketTypeLabel: String? = null,
    val ticketTypeColor: String? = null,
)

/** Real-time attendance counters for the event being checked in. */
data class AttendanceResponse(
    val checkedIn: Long,
    val confirmed: Long,
)

/**
 * One guest in the offline roster a validator pre-syncs before the event. Carries
 * everything needed to scan/search and decide check-in eligibility offline; guests
 * without a ticket (not yet confirmed) appear with null ticket fields.
 */
data class RosterEntry(
    val guestId: UUID,
    val name: String,
    val email: String?,
    val phoneNumber: String?,
    val rsvpStatus: String,
    val ticketCode: String?,
    val ticketStatus: String?,
    val checkedInAt: Instant?,
    val checkedInBy: String?,
    /**
     * Catégorie d'accès (JIKU-93). Portée par le roster parce que c'est hors ligne
     * que le portier en a le plus besoin : sans réseau, l'appareil ne peut la lire
     * nulle part ailleurs.
     */
    val ticketTypeLabel: String? = null,
    val ticketTypeColor: String? = null,
    /** Whether the guest still owes the organization (JIKU-110): offline, the device refuses entry itself. */
    val paymentStatus: TicketPaymentStatus? = null,
)

/** A batch of check-ins captured offline, each stamped with its on-device scan time. */
data class SyncRequest(
    @field:Valid
    val items: List<SyncItem>,
)

data class SyncItem(
    @field:NotBlank
    val ticketCode: String,
    val scannedAt: Instant,
)

/** Per-item reconciliation result returned to the device after a sync. */
data class SyncResultEntry(
    val ticketCode: String,
    val outcome: String,
    val guestName: String?,
    val checkedInAt: Instant?,
    val checkedInBy: String?,
)

/**
 * Context shown to an operator opening an event's door: the event being staffed,
 * the organizer's branding, the operator's label and what they may do there, and
 * live attendance counters.
 * Times are UTC instants rendered in [timezone] by the client.
 */
data class ValidatorContextResponse(
    val eventName: String,
    /** The event's lifecycle status; CANCELLED means check-in is closed for good. */
    val eventStatus: String,
    val startDateTime: Instant?,
    val timezone: String,
    val eventLocation: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
    val validatorLabel: String,
    val actions: Set<OperatorAction>,
    val checkedIn: Long,
    val confirmed: Long,
)
