package com.jiku.admin.internal

import com.jiku.booking.AdminPaymentDeclarationView
import com.jiku.booking.BookingModuleApi
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Back-office queue for booking payment declarations (JIKU-55). Verifying a
 * DEPOSIT declaration provisions the customer's tenant and draft event through
 * the booking module — never here, this controller only routes the action and
 * records the audit entry, same split as [AdminPaymentController].
 */
@RestController
@RequestMapping("/admin/booking-payments")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminBookingPaymentController(
    private val bookingModuleApi: BookingModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<AdminPaymentDeclarationView> = bookingModuleApi.adminListPaymentDeclarations(status, page, size)

    @PostMapping("/{id}/verify")
    fun verify(
        @PathVariable id: UUID,
    ): AdminPaymentDeclarationView {
        val view = bookingModuleApi.adminVerifyPaymentDeclaration(id, currentAdminId())
        auditService.record(
            action = "BOOKING_PAYMENT_VERIFIED",
            target = "booking-payment:$id",
            note = "reference: ${view.transactionReference}",
        )
        return view
    }

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RejectDeclarationRequest,
    ): AdminPaymentDeclarationView {
        val view = bookingModuleApi.adminRejectPaymentDeclaration(id, currentAdminId(), request.reason)
        auditService.record(action = "BOOKING_PAYMENT_REJECTED", target = "booking-payment:$id", note = request.reason)
        return view
    }

    private fun currentAdminId(): String = requireNotNull(SecurityContextHolder.getContext().authentication?.name)
}

data class RejectDeclarationRequest(
    @field:NotBlank val reason: String,
)
