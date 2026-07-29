package com.jiku.booking.internal

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateBookingRequest(
    @field:NotBlank val customerName: String,
    @field:NotBlank val customerPhone: String,
    @field:NotBlank @field:Email val customerEmail: String,
    val eventType: BookingEventType,
    @field:Future val eventDate: LocalDate,
    @field:Positive val guestCountEstimate: Long,
    val acquisitionSource: String? = null,
)

data class BookingQuote(
    val tier: String,
    val currency: String,
    val totalAmountMinor: Long,
    val depositAmountMinor: Long,
    val balanceAmountMinor: Long,
)

data class BookingCreationResult(
    val id: UUID,
    val accessToken: String,
    val tier: String,
    val currency: String,
    val totalAmountMinor: Long,
    val depositAmountMinor: Long,
    val balanceAmountMinor: Long,
    val balanceDueDate: LocalDate,
    val status: String,
)

data class BookingStatusView(
    val id: UUID,
    val customerName: String,
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
    val createdAt: Instant,
)

data class DeclarePaymentRequest(
    @field:Positive val amountMinor: Long,
    val kind: PaymentDeclarationKind,
    val operator: MobileMoneyOperator,
    @field:NotBlank val transactionReference: String,
)

data class PaymentDeclarationResult(
    val id: UUID,
    val bookingId: UUID,
    val verificationStatus: String,
    val declaredAt: Instant,
)

/** Payee details for the Mobile Money instructions page — configuration, never a literal. */
data class BookingPayeeDetails(
    val payeeName: String?,
    val orangeMoneyNumber: String?,
    val mtnMomoNumber: String?,
)
