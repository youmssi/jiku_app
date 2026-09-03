package com.jiku.backoffice.internal

import com.jiku.money.AdminPaymentView
import com.jiku.money.BillingModuleApi
import jakarta.validation.Valid
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

/**
 * Back-office payments desk (JIKU-41). Confirming records the transfer reference
 * the operator observed on the receiving account (audit note), then unlocks the
 * tier through the billing module's exposed API.
 */
@RestController
@RequestMapping("/admin/payments")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminPaymentController(
    private val billingModuleApi: BillingModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) provider: String?,
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<AdminPaymentView> = billingModuleApi.adminListPayments(status, provider, tenantId, page, size)

    @PostMapping("/{id}/confirm")
    fun confirm(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ConfirmPaymentRequest,
    ): AdminPaymentView {
        val view = billingModuleApi.adminConfirmManualPayment(id)
        auditService.record(
            action = "PAYMENT_CONFIRMED",
            target = "payment:$id",
            note = "transaction: ${request.transactionReference}",
        )
        return view
    }

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RejectPaymentRequest,
    ): AdminPaymentView {
        val view = billingModuleApi.adminRejectManualPayment(id, request.reason)
        auditService.record(action = "PAYMENT_REJECTED", target = "payment:$id", note = request.reason)
        return view
    }
}

data class ConfirmPaymentRequest(
    @field:NotBlank(message = "The observed transaction reference is required")
    val transactionReference: String,
)

data class RejectPaymentRequest(
    @field:NotBlank(message = "A rejection reason is required")
    val reason: String,
)
