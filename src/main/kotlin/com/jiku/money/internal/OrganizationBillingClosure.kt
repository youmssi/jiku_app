package com.jiku.money.internal

import com.jiku.shared.OrganizationClosed
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Closes the transfer requests still waiting for an organization deleted with
 * its owner's account (JIKU-316). Invoices, payments and their amounts stay
 * untouched: accounting law keeps them for ten years.
 */
@Component
class OrganizationBillingClosure(
    private val manualPayments: ManualPaymentService,
) {
    @EventListener
    fun on(event: OrganizationClosed) {
        manualPayments.openRequests().forEach { manualPayments.cancel(it.paymentId) }
    }
}
