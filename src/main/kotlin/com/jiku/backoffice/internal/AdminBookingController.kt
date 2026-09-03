package com.jiku.backoffice.internal

import com.jiku.booking.AdminBookingCancellationView
import com.jiku.booking.AdminBookingView
import com.jiku.booking.BookingModuleApi
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Back-office desk for deposit reservations (JIKU-55): the booking list and
 * cancellation (which computes, but does not itself transfer, the refund due
 * under the sliding-scale policy — the actual transfer is a manual Mobile
 * Money action, like every payment in this flow).
 */
@RestController
@RequestMapping("/admin/bookings")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminBookingController(
    private val bookingModuleApi: BookingModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<AdminBookingView> = bookingModuleApi.adminListBookings(status, page, size)

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
    ): AdminBookingCancellationView {
        val view = bookingModuleApi.adminCancelBooking(id)
        auditService.record(
            action = "BOOKING_CANCELLED",
            target = "booking:$id",
            note = "refund due: ${view.refundAmountMinor} ${view.currency}",
        )
        return view
    }
}
