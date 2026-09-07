package com.jiku.backoffice.internal

import com.jiku.booking.AdminBookingCancellationView
import com.jiku.booking.AdminBookingRefundView
import com.jiku.booking.AdminBookingView
import com.jiku.booking.BookingModuleApi
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class RefundBookingRequest(
    @field:Min(1) val amountMinor: Long,
    @field:NotBlank val reason: String,
)

/**
 * Back-office desk for deposit reservations (JIKU-55/75): the booking list,
 * cancellation (which computes, but does not itself transfer, the refund due
 * under the sliding-scale policy), and the executed-refund action — the actual
 * Mobile Money transfer is manual and is recorded here afterwards, with its
 * reason and the emitted credit note.
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

    @PostMapping("/{id}/refund")
    fun refund(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RefundBookingRequest,
    ): AdminBookingRefundView {
        val view = bookingModuleApi.adminRefundBooking(id, request.amountMinor, request.reason)
        auditService.record(
            action = "BOOKING_REFUNDED",
            target = "booking:$id",
            note = "refunded ${view.amountMinor} ${view.currency} — credit note ${view.creditNoteNumber ?: "-"}",
        )
        return view
    }
}
