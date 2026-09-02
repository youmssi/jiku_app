package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A Mobile Money payment attempt for an event's usage tier (JIKU-33). Recorded on
 * initiation and updated when the provider confirms the outcome server-side; every
 * attempt is retained (with provider reference, amount, currency, timestamps) for
 * reconciliation. Tenant-scoped.
 */
@Entity
@Table(name = "payment")
class Payment(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "tier", nullable = false, updatable = false)
    val tier: String,
    @Column(name = "amount_minor", nullable = false, updatable = false)
    val amountMinor: Long,
    @Column(name = "currency", nullable = false, updatable = false)
    val currency: String,
    @Column(name = "provider", nullable = false, updatable = false)
    val provider: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "provider_reference")
    var providerReference: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: PaymentStatus = PaymentStatus.PENDING

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
