package com.jiku.ticket.internal

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
 * Ligne d'idempotence d'un rappel de rendez-vous (JIKU-89) : une ligne par
 * (billet, décalage), réservée par le balayage avant l'envoi. La contrainte
 * d'unicité rend la réservation atomique — deux instances du balayage ne
 * réservent jamais deux fois le même rappel.
 */
@Entity
@Table(
    name = "appointment_reminder",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_appointment_reminder_ticket_offset", columnNames = ["ticket_id", "offset_minutes"]),
    ],
)
class AppointmentReminder(
    @Column(name = "ticket_id", nullable = false)
    val ticketId: UUID,
    @Column(name = "service_id", nullable = false)
    val serviceId: UUID,
    @Column(name = "offset_minutes", nullable = false)
    val offsetMinutes: Int,
    @Column(name = "due_at", nullable = false)
    val dueAt: Instant,
    @Column(name = "channel", nullable = false, length = 16)
    var channel: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ReminderStatus = ReminderStatus.PENDING

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    @Column(name = "error", length = 500)
    var error: String? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null
}
