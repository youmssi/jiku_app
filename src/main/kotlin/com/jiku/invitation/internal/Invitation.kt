package com.jiku.invitation.internal

import com.jiku.catalog.InvitationChannel
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
 * Tracks the delivery of one guest's invitation on one channel. Tenant-scoped.
 * One record per (guest, channel); re-sending updates the existing record.
 */
@Entity
@Table(
    name = "invitation",
    uniqueConstraints = [UniqueConstraint(name = "uq_invitation_guest_channel", columnNames = ["guest_id", "channel"])],
)
class Invitation(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "guest_id", nullable = false, updatable = false)
    val guestId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false)
    val channel: InvitationChannel,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: InvitationStatus = InvitationStatus.PENDING

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    @Column(name = "last_error")
    var lastError: String? = null

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
