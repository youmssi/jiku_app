package com.jiku.money.internal

import com.jiku.money.ManualPaymentInstructions
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The "own WhatsApp number" add-on (ADR 105): whether the organization has it,
 * and a request to pay for months of it through the manual Mobile Money circuit.
 */
@RestController
@RequestMapping("/billing/whatsapp-number")
class OwnWhatsAppNumberController(
    private val ownNumber: OwnWhatsAppNumberService,
    private val manualPaymentService: ManualPaymentService,
) {
    @GetMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    fun ownNumberView(): OwnWhatsAppNumberView = ownNumber.view()

    @PostMapping("/request")
    @PreAuthorize("hasRole('ORGANIZER_MANAGER')")
    fun requestOwnNumber(
        @Valid @RequestBody request: PackRequest,
    ): ManualPaymentInstructions = manualPaymentService.requestOwnWhatsAppNumber(request.months)
}
