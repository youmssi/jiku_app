package com.jiku.money.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Where the organization stands with what it pays for, for the app's reminder (JIKU-254). */
@RestController
@RequestMapping("/billing/status")
@PreAuthorize("hasRole('ORGANIZER')")
class BillingStatusController(
    private val billingStatus: BillingStatusService,
) {
    @GetMapping
    fun status(): BillingStatusView = billingStatus.status()
}
