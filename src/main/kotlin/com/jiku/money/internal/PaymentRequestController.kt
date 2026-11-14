package com.jiku.money.internal

import com.jiku.money.ManualPaymentInstructions
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The organization's transfer requests still waiting (JIKU-307): all of them in
 * one list, each with its reference and payee, and the two things the
 * organizer can do with one: cancel it, or say the transfer is sent.
 */
@RestController
@RequestMapping("/billing/payment-requests")
@PreAuthorize("hasRole('ORGANIZER')")
class PaymentRequestController(
    private val manualPayments: ManualPaymentService,
) {
    @GetMapping
    fun open(): List<ManualPaymentInstructions> = manualPayments.openRequests()

    @PostMapping("/{paymentId}/cancel")
    fun cancel(
        @PathVariable paymentId: UUID,
    ): ManualPaymentInstructions = manualPayments.cancel(paymentId)

    @PostMapping("/{paymentId}/transfer-sent")
    fun transferSent(
        @PathVariable paymentId: UUID,
    ): ManualPaymentInstructions = manualPayments.declareTransfer(paymentId)
}
