package com.jiku.ticket

import com.jiku.shared.ClientCharge
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The ticketing module's public API. The RSVP flow issues a ticket on
 * confirmation and cancels it on decline; check-in resolves a ticket by its code
 * (or its guest) and atomically transitions it to checked-in. Reads/writes are
 * tenant-scoped by the persistence-layer filter.
 */
interface TicketingModuleApi {
    /** [charge] is what the guest owes for a sold category; null for a free one. */
    fun issueTicket(
        eventId: UUID,
        guestId: UUID,
        ticketTypeId: UUID? = null,
        charge: ClientCharge? = null,
    ): TicketInfo

    fun cancelByGuest(guestId: UUID)

    /**
     * Hands [fromGuestId]'s ticket to [toGuestId] (JIKU-64): the sender's ticket is
     * cancelled and the recipient's carries the same category and the same payment
     * — a place already paid for stays paid.
     */
    fun transferTicket(
        fromGuestId: UUID,
        toGuestId: UUID,
    ): TicketInfo

    /**
     * Émet le billet d'un rendez-vous sans compte (JIKU-87) : billet sans
     * événement portant le créneau, le service et le nom du professionnel.
     * Le nom et le téléphone du client sont figés dessus (JIKU-88) pour que la
     * ligne du jour se rende sans jointure. Renvoie le code (le QR sera rendu côté web).
     */
    fun issueAppointment(
        guestId: UUID,
        startsAt: Instant,
        endsAt: Instant,
        serviceId: UUID,
        professionalName: String?,
        clientName: String,
        clientPhone: String,
        charge: ClientCharge? = null,
    ): String

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

    /**
     * Instants de check-in d'un événement (projection, sans les entités complètes) —
     * la matière première de la courbe d'arrivées de l'analytique.
     */
    fun checkInInstants(eventId: UUID): List<Instant>

    /**
     * La ligne du jour d'un service (JIKU-88) : les tickets de la journée (par le
     * créneau pour un rendez-vous, par l'arrivée pour un sans-rendez-vous), triés
     * pour l'écran du comptoir. Bornée par [dayStart] (inclus) et [dayEnd] (exclu),
     * instants UTC calculés par l'appelant dans le fuseau du service.
     */
    fun serviceLine(
        serviceId: UUID,
        dayStart: Instant,
        dayEnd: Instant,
    ): List<LineTicket>

    /**
     * Appelle le suivant selon la règle (§4.1) : un rendez-vous en cours dont le
     * client est arrivé, sinon la plus longue attente. La transition EN_ATTENTE →
     * APPELÉ est réclamée atomiquement : deux appareils qui appellent en même temps
     * n'obtiennent jamais la même personne — le perdant resélectionne. Renvoie la
     * personne appelée, ou null si personne n'attend.
     */
    fun callNext(
        serviceId: UUID,
        dayStart: Instant,
        dayEnd: Instant,
        now: Instant,
        toleranceMinutes: Long,
        counter: String? = null,
    ): LineTicket?

    /**
     * Arrivée au comptoir d'un rendez-vous du jour : ISSUED → EN_ATTENTE,
     * horodatée, avec son rang du jour — alloué séquentiellement par (service,
     * [rankDay]) sous verrou. La garde sur l'état rend la transition atomique
     * (double scan refusé).
     */
    fun arriveByCode(
        serviceId: UUID,
        ticketCode: String,
        at: Instant,
        dayStart: Instant,
        dayEnd: Instant,
        rankDay: LocalDate,
    ): LineActionResult

    /** Appel d'une personne précise : EN_ATTENTE → APPELÉ. */
    fun callByCode(
        serviceId: UUID,
        ticketCode: String,
        counter: String? = null,
    ): LineActionResult

    /** Prise en charge : APPELÉ (ou ABSENT rappelé) → EN_COURS. */
    fun presentByCode(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult

    /** Fin de la prise en charge : EN_COURS → TERMINÉ. */
    fun finishByCode(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult

    /** Absent après appel : APPELÉ → ABSENT. */
    fun noShowByCode(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult

    /**
     * Crée un sans-rendez-vous au comptoir (JIKU-88) : le client est déjà présent,
     * son ticket naît en EN_ATTENTE avec son rang du jour — alloué
     * séquentiellement par (service, [rankDay]) sous verrou. L'invité est
     * matérialisé par le module invitation, qui appelle cette méthode.
     */
    fun issueWalkIn(
        guestId: UUID,
        serviceId: UUID,
        clientName: String,
        clientPhone: String,
        professionalName: String?,
        arrivedAt: Instant,
        dayStart: Instant,
        dayEnd: Instant,
        rankDay: LocalDate,
        charge: ClientCharge? = null,
    ): LineTicket

    /**
     * Records that the holder paid the organization what the ticket owes
     * (JIKU-110), attributing it to [paidBy]. Atomic: of two operators confirming
     * at once, one records it and the other is told it is already paid.
     */
    fun markPaidByCode(
        ticketCode: String,
        method: TicketPaymentMethod,
        paidBy: String,
    ): TicketPaymentResult
}

enum class TicketPaymentStatus {
    NOT_REQUIRED,

    /** Must be paid before the ticket is used: no entry, no service. */
    DUE,

    /** Becomes [DUE] when the service ends. */
    DUE_AFTER_SERVICE,
    PAID,
}

/** How the holder paid the organization; Jikū never handles the money. */
enum class TicketPaymentMethod {
    MOBILE_MONEY,
    PAYMENT_LINK,
    CASH,
}

/** An operator confirming that a ticket's holder paid the organization. */
data class MarkPaidRequest(
    val method: TicketPaymentMethod,
)

data class TicketPaymentResult(
    val outcome: TicketPaymentOutcome,
    val ticket: TicketInfo? = null,
)

enum class TicketPaymentOutcome {
    PAID,

    /** Nothing is owed on this ticket, or it was already paid. */
    NOT_DUE,
    NOT_FOUND,
}

/**
 * Une ligne de la journée d'un service : l'essentiel du ticket, avec le client,
 * pour que l'écran du comptoir se rende sans autre lecture.
 */
data class LineTicket(
    val id: UUID,
    val ticketCode: String,
    val kind: String,
    val status: String,
    val clientName: String? = null,
    val clientPhone: String? = null,
    val startsAt: Instant? = null,
    val endsAt: Instant? = null,
    val arrivedAt: Instant? = null,
    val dayRank: Int? = null,
    val paymentStatus: TicketPaymentStatus = TicketPaymentStatus.NOT_REQUIRED,
    val amountDueMinor: Long? = null,
    val amountDueCurrency: String? = null,
    /** The counter the client was called to, once called (JIKU-113). */
    val counter: String? = null,
)

data class LineActionResult(
    val outcome: LineOutcome,
    val ticket: LineTicket? = null,
)

enum class LineOutcome {
    /** Transition appliquée. */
    OK,

    /** Aucun ticket avec ce code dans le tenant courant. */
    NOT_FOUND,

    /** Le ticket existe mais n'est pas dans l'état attendu (ou pas du bon service / jour). */
    WRONG_STATE,

    /** Le service ne peut pas commencer : le ticket n'est pas encore payé. */
    PAYMENT_DUE,
}

data class TicketInfo(
    val id: UUID,
    /** Null for a service ticket (appointment or walk-in), which belongs to no event. */
    val eventId: UUID?,
    /** The service of an appointment or walk-in; null for an event ticket. */
    val serviceId: UUID? = null,
    val guestId: UUID,
    val ticketCode: String,
    val status: String,
    val issuedAt: Instant,
    val checkedInAt: Instant? = null,
    val checkedInBy: String? = null,
    /** Catégorie d'accès figée à l'émission (JIKU-93). */
    val ticketTypeId: UUID? = null,
    val paymentStatus: TicketPaymentStatus = TicketPaymentStatus.NOT_REQUIRED,
    val amountDueMinor: Long? = null,
    val amountDueCurrency: String? = null,
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

    /** The ticket is not paid yet: no entry until the organization confirms the payment. */
    PAYMENT_DUE,
}

data class AttendanceStats(
    val checkedIn: Long,
    val confirmed: Long,
)
