package com.jiku.booking.internal

import com.jiku.booking.AdminBookingCancellationView
import com.jiku.booking.AdminBookingRefundView
import com.jiku.booking.AdminBookingView
import com.jiku.booking.AdminPaymentDeclarationView
import com.jiku.booking.BookingModuleApi
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class BookingModuleApiService(
    private val bookingService: BookingService,
) : BookingModuleApi {
    override fun adminListBookings(
        status: String?,
        page: Int,
        size: Int,
    ): List<AdminBookingView> = bookingService.adminListBookings(status, page, size)

    override fun adminCancelBooking(bookingId: UUID): AdminBookingCancellationView = bookingService.adminCancelBooking(bookingId)

    override fun adminRefundBooking(
        bookingId: UUID,
        amountMinor: Long,
        reason: String,
    ): AdminBookingRefundView = bookingService.adminRefundBooking(bookingId, amountMinor, reason)

    override fun adminListPaymentDeclarations(
        status: String?,
        page: Int,
        size: Int,
    ): List<AdminPaymentDeclarationView> = bookingService.adminListPaymentDeclarations(status, page, size)

    override fun adminVerifyPaymentDeclaration(
        declarationId: UUID,
        adminId: String,
    ): AdminPaymentDeclarationView = bookingService.adminVerifyPaymentDeclaration(declarationId, adminId)

    override fun adminRejectPaymentDeclaration(
        declarationId: UUID,
        adminId: String,
        reason: String,
    ): AdminPaymentDeclarationView = bookingService.adminRejectPaymentDeclaration(declarationId, adminId, reason)
}
