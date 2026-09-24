package com.jiku.ticket.internal

import com.jiku.shared.BaseTenantEntity
import com.jiku.shared.ClientCharge
import com.jiku.ticket.TicketPaymentMethod
import com.jiku.ticket.TicketPaymentStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * A guest's ticket for an event. Tenant-scoped. One ticket per guest; the
 * [ticketCode] is cryptographically random and non-sequential.
 */
@Entity
@Table(
    name = "ticket",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_ticket_guest", columnNames = ["guest_id"]),
        UniqueConstraint(name = "uq_ticket_code", columnNames = ["ticket_code"]),
    ],
)
class Ticket(
    /** Nul pour un billet de rendez-vous (JIKU-87) : il n'appartient à aucun événement. */
    @Column(name = "event_id", updatable = false)
    val eventId: UUID?,
    @Column(name = "guest_id", nullable = false, updatable = false)
    val guestId: UUID,
    @Column(name = "ticket_code", nullable = false, updatable = false)
    val ticketCode: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: TicketStatus = TicketStatus.ISSUED

    @Column(name = "issued_at", nullable = false, updatable = false)
    val issuedAt: Instant = Instant.now()

    @Column(name = "checked_in_at")
    var checkedInAt: Instant? = null

    @Column(name = "checked_in_by")
    var checkedInBy: String? = null

    /**
     * Catégorie d'accès, recopiée depuis l'invité à l'émission (JIKU-93). Figée
     * comme le reste du billet : si l'organisateur reclasse l'invité ensuite, le
     * billet déjà remis continue de dire ce qu'il disait.
     */
    @Column(name = "ticket_type_id", updatable = false)
    var ticketTypeId: UUID? = null

    /**
     * Ce que porte le ticket : une invitation (défaut, tous les tickets
     * existants) ou, plus tard, un rendez-vous sur créneau (JIKU-83).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    var kind: TicketKind = TicketKind.INVITATION

    /** Début du créneau ; nul pour un ticket sans créneau (invitation, sans-rendez-vous). */
    @Column(name = "starts_at")
    var startsAt: Instant? = null

    /** Fin du créneau ; nul pour un ticket sans créneau. */
    @Column(name = "ends_at")
    var endsAt: Instant? = null

    /** Heure de présence effective (arrivée à la porte / au comptoir). */
    @Column(name = "arrived_at")
    var arrivedAt: Instant? = null

    /** Rang du jour, séquentiel par service et par jour ; rempli par la ligne du jour (JIKU-88). */
    @Column(name = "day_rank")
    var dayRank: Int? = null

    /** Service réservé, pour un billet de rendez-vous (JIKU-87) ; nul pour une invitation. */
    @Column(name = "service_id", updatable = false)
    var serviceId: UUID? = null

    /** Nom du professionnel, figé à l'émission (JIKU-87) ; affiché seul sur le billet. */
    @Column(name = "professional_name", length = 120)
    var professionalName: String? = null

    /**
     * Nom et téléphone du client, figés à l'émission (JIKU-88) : la ligne du jour
     * se rend au comptoir sans jointure vers l'invité. Nul pour une invitation.
     */
    @Column(name = "client_name", length = 120, updatable = false)
    var clientName: String? = null

    @Column(name = "client_phone", length = 32, updatable = false)
    var clientPhone: String? = null

    /** Whether the holder owes the organization for this ticket (JIKU-110). */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 24)
    var paymentStatus: TicketPaymentStatus = TicketPaymentStatus.NOT_REQUIRED

    /** Amount owed, fixed at issue: a later price change never rewrites it. Null when free. */
    @Column(name = "amount_due_minor", updatable = false)
    var amountDueMinor: Long? = null

    @Column(name = "amount_due_currency", length = 3, updatable = false)
    var amountDueCurrency: String? = null

    @Column(name = "paid_at")
    var paidAt: Instant? = null

    /** The operator who confirmed the payment. */
    @Column(name = "paid_by")
    var paidBy: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "paid_with", length = 16)
    var paidWith: TicketPaymentMethod? = null

    /** Records what the holder owes; a free ticket (null [charge]) owes nothing. */
    fun charge(charge: ClientCharge?) {
        if (charge == null) return
        amountDueMinor = charge.amountMinor
        amountDueCurrency = charge.currency
        paymentStatus = if (charge.dueAfterService) TicketPaymentStatus.DUE_AFTER_SERVICE else TicketPaymentStatus.DUE
    }
}
