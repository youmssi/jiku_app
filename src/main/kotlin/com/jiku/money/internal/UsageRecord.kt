package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * The metering snapshot for one event (JIKU-32). Tenant-scoped; one per event. The
 * count columns mirror the invitation module's authoritative figures (refreshed on
 * read), while [unlockedAllowance] is the stateful entitlement — the free tier by
 * default, raised when a paid tier is unlocked (JIKU-33).
 */
@Entity
@Table(
    name = "usage_record",
    uniqueConstraints = [UniqueConstraint(name = "uq_usage_record_event", columnNames = ["tenant_id", "event_id"])],
)
class UsageRecord(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "unlocked_allowance", nullable = false)
    var unlockedAllowance: Long,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "guests_imported", nullable = false)
    var guestsImported: Long = 0

    @Column(name = "invited_guests", nullable = false)
    var invitedGuests: Long = 0

    @Column(name = "invitations_sent_email", nullable = false)
    var invitationsSentEmail: Long = 0

    @Column(name = "invitations_sent_whatsapp", nullable = false)
    var invitationsSentWhatsapp: Long = 0

    /**
     * Amount already paid toward this event outside the normal payment flow —
     * today, only a booking deposit/balance (JIKU-57) — netted off the price of
     * the next manual payment request rather than charged twice. Spent (zeroed)
     * the moment it is applied to a request.
     */
    @Column(name = "prepaid_amount_minor", nullable = false)
    var prepaidAmountMinor: Long = 0

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
