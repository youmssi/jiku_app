package com.jiku.booking

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The booking module's public API — consumed only by the admin module's
 * back-office controllers (JIKU-55). The public-facing reservation endpoints a
 * prospect uses have no admin/organizer session to route through, so they live
 * as ordinary controllers inside the booking module itself rather than behind
 * this interface.
 */
interface BookingModuleApi {
    fun adminListBookings(
        status: String?,
        page: Int,
        size: Int,
    ): List<AdminBookingView>

    /** Cancels a booking and computes the refund due under the JIKU-55 sliding-scale policy — the transfer itself is manual, like every payment in this flow. */
    fun adminCancelBooking(bookingId: UUID): AdminBookingCancellationView

    fun adminListPaymentDeclarations(
        status: String?,
        page: Int,
        size: Int,
    ): List<AdminPaymentDeclarationView>

    /** Verifies a declaration: on a DEPOSIT it also provisions the customer's tenant and draft event. */
    fun adminVerifyPaymentDeclaration(
        declarationId: UUID,
        adminId: String,
    ): AdminPaymentDeclarationView

    fun adminRejectPaymentDeclaration(
        declarationId: UUID,
        adminId: String,
        reason: String,
    ): AdminPaymentDeclarationView
}

data class AdminBookingView(
    val id: UUID,
    val customerName: String,
    val customerPhone: String,
    val customerEmail: String,
    val eventType: String,
    val eventDate: LocalDate,
    val guestCountEstimate: Long,
    val tier: String,
    val currency: String,
    val totalAmountMinor: Long,
    val depositAmountMinor: Long,
    val balanceAmountMinor: Long,
    val balanceDueDate: LocalDate,
    val status: String,
    val tenantId: String?,
    val eventId: UUID?,
    val acquisitionSource: String?,
    val createdAt: Instant,
)

data class AdminBookingCancellationView(
    val id: UUID,
    val status: String,
    val refundAmountMinor: Long,
    val currency: String,
)

data class AdminPaymentDeclarationView(
    val id: UUID,
    val bookingId: UUID,
    val customerName: String,
    val amountMinor: Long,
    val currency: String,
    val kind: String,
    val operator: String,
    val transactionReference: String,
    val declaredAt: Instant,
    val verificationStatus: String,
    val verifiedBy: String?,
    val verifiedAt: Instant?,
    val rejectionReason: String?,
)
