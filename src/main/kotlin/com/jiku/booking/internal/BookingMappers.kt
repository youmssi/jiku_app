package com.jiku.booking.internal

import com.jiku.booking.AdminBookingView
import com.jiku.booking.AdminPaymentDeclarationView

fun Booking.toCreationResult(
    accessToken: String,
    currency: String,
) = BookingCreationResult(
    id = requireNotNull(id),
    accessToken = accessToken,
    tier = tier,
    currency = currency,
    totalAmountMinor = totalAmountMinor,
    depositAmountMinor = depositAmountMinor,
    balanceAmountMinor = balanceAmountMinor,
    balanceDueDate = balanceDueDate,
    status = status.name,
)

fun Booking.toStatusView(currency: String) =
    BookingStatusView(
        id = requireNotNull(id),
        customerName = customerName,
        eventType = eventType.name,
        eventDate = eventDate,
        guestCountEstimate = guestCountEstimate,
        tier = tier,
        currency = currency,
        totalAmountMinor = totalAmountMinor,
        depositAmountMinor = depositAmountMinor,
        balanceAmountMinor = balanceAmountMinor,
        balanceDueDate = balanceDueDate,
        status = status.name,
        createdAt = createdAt,
    )

fun Booking.toAdminView(currency: String) =
    AdminBookingView(
        id = requireNotNull(id),
        customerName = customerName,
        customerPhone = customerPhone,
        customerEmail = customerEmail,
        eventType = eventType.name,
        eventDate = eventDate,
        guestCountEstimate = guestCountEstimate,
        tier = tier,
        currency = currency,
        totalAmountMinor = totalAmountMinor,
        depositAmountMinor = depositAmountMinor,
        balanceAmountMinor = balanceAmountMinor,
        balanceDueDate = balanceDueDate,
        status = status.name,
        tenantId = tenantId,
        eventId = eventId,
        acquisitionSource = acquisitionSource,
        createdAt = createdAt,
    )

fun PaymentDeclaration.toResult() =
    PaymentDeclarationResult(
        id = requireNotNull(id),
        bookingId = bookingId,
        verificationStatus = verificationStatus.name,
        declaredAt = declaredAt,
    )

fun PaymentDeclaration.toAdminView(
    customerName: String,
    currency: String,
) = AdminPaymentDeclarationView(
    id = requireNotNull(id),
    bookingId = bookingId,
    customerName = customerName,
    amountMinor = amountMinor,
    currency = currency,
    kind = kind.name,
    operator = operator.name,
    transactionReference = transactionReference,
    declaredAt = declaredAt,
    verificationStatus = verificationStatus.name,
    verifiedBy = verifiedBy,
    verifiedAt = verifiedAt,
    rejectionReason = rejectionReason,
)
