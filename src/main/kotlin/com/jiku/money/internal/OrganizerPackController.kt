package com.jiku.money.internal

import com.jiku.money.ManualPaymentInstructions
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The Organizer Pack (ADR 105): its current month, and requests to pay for
 * months of it or for extra guests, through the manual Mobile Money circuit.
 */
@RestController
@RequestMapping("/billing/pack")
class OrganizerPackController(
    private val organizerPack: OrganizerPackService,
    private val manualPaymentService: ManualPaymentService,
) {
    @GetMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    fun packView(): PackView = organizerPack.view()

    @PostMapping("/request")
    @PreAuthorize("hasRole('ORGANIZER_MANAGER')")
    fun requestPack(
        @Valid @RequestBody request: PackRequest,
    ): ManualPaymentInstructions = manualPaymentService.requestPack(request.months)

    @PostMapping("/extra")
    @PreAuthorize("hasRole('ORGANIZER_MANAGER')")
    fun requestPackExtra(
        @Valid @RequestBody request: PackExtraRequest,
    ): ManualPaymentInstructions = manualPaymentService.requestPackExtra(request.blocks)
}

data class PackRequest(
    @field:Min(1)
    val months: Int,
)

data class PackExtraRequest(
    @field:Min(1)
    val blocks: Int,
)
