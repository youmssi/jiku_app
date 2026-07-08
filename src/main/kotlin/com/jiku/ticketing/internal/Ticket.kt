package com.jiku.ticketing.internal

import com.jiku.shared.BaseTenantEntity
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
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
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
}
