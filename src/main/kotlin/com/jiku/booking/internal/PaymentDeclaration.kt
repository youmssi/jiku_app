package com.jiku.booking.internal

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
 * A customer's claim to have sent a Mobile Money transfer against a [Booking]
 * (JIKU-55). Never auto-verified — [verificationStatus] only ever moves to
 * VERIFIED or REJECTED through an explicit admin action. [transactionReference]
 * is guarded against reuse by [BookingRepository]'s partial unique index
 * (`transaction_reference` unique among rows not already marked DUPLICATE),
 * closing the reused-screenshot fraud pattern documented in the target market
 * without losing the audit trail of the duplicate attempt itself.
 */
@Entity
@Table(name = "booking_payment_declaration")
class PaymentDeclaration(
    @Column(name = "booking_id", nullable = false)
    var bookingId: UUID,
    @Column(name = "amount_minor", nullable = false)
    var amountMinor: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    var kind: PaymentDeclarationKind,
    @Enumerated(EnumType.STRING)
    @Column(name = "operator", nullable = false)
    var operator: MobileMoneyOperator,
    @Column(name = "transaction_reference", nullable = false)
    var transactionReference: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "declared_at", nullable = false)
    var declaredAt: Instant = Instant.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    var verificationStatus: PaymentVerificationStatus = PaymentVerificationStatus.PENDING

    @Column(name = "verified_by")
    var verifiedBy: String? = null

    @Column(name = "verified_at")
    var verifiedAt: Instant? = null

    @Column(name = "rejection_reason")
    var rejectionReason: String? = null
}
