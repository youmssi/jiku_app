package com.jiku.booking.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A prospect's date reservation against a 30% deposit (JIKU-55) — the core
 * mechanic the GTM launch plan is built on. Deliberately **not** tenant-scoped:
 * at creation the prospect has no account yet, so this is platform-level data,
 * visible to the admin desk until [tenantId]/[eventId] are backfilled once the
 * deposit is verified and an organizer account is provisioned.
 */
@Entity
@Table(name = "booking")
class Booking(
    @Column(name = "customer_name", nullable = false)
    var customerName: String,
    @Column(name = "customer_phone", nullable = false)
    var customerPhone: String,
    @Column(name = "customer_email", nullable = false)
    var customerEmail: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    var eventType: BookingEventType,
    @Column(name = "event_date", nullable = false)
    var eventDate: LocalDate,
    @Column(name = "guest_count_estimate", nullable = false)
    var guestCountEstimate: Long,
    @Column(name = "tier", nullable = false)
    var tier: String,
    @Column(name = "total_amount_minor", nullable = false)
    var totalAmountMinor: Long,
    @Column(name = "deposit_rate", nullable = false)
    var depositRate: BigDecimal,
    @Column(name = "deposit_amount_minor", nullable = false)
    var depositAmountMinor: Long,
    @Column(name = "balance_amount_minor", nullable = false)
    var balanceAmountMinor: Long,
    @Column(name = "balance_due_date", nullable = false)
    var balanceDueDate: LocalDate,
    @Column(name = "access_token_hash", nullable = false, unique = true)
    var accessTokenHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: BookingStatus = BookingStatus.AWAITING_DEPOSIT

    /** Backfilled once the deposit is verified and a tenant is provisioned. */
    @Column(name = "tenant_id")
    var tenantId: String? = null

    /** Backfilled alongside [tenantId] — the pre-filled draft event. */
    @Column(name = "event_id")
    var eventId: UUID? = null

    /** From the `?src=` marketing link that led to this reservation (JIKU-59). */
    @Column(name = "acquisition_source")
    var acquisitionSource: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
