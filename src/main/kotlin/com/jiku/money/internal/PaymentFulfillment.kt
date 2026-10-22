package com.jiku.money.internal

import org.springframework.stereotype.Component

/**
 * What a succeeded payment grants, by kind (JIKU-164): an event's guests, months
 * of a services plan or of Pro Organisateur, or extra pack guests. The one place both confirmation paths go through — an
 * admin confirming a manual transfer and a provider confirming an online payment —
 * so a kind is never granted differently depending on how it was paid.
 */
@Component
class PaymentFulfillment(
    private val eventGuests: EventGuestsUnlock,
    private val subscriptionService: SubscriptionService,
    private val organizerPack: OrganizerPackService,
    private val commission: CommissionService,
) {
    /** Grants what [payment] paid for. Runs in the caller's transaction, under the payment's tenant. */
    fun fulfill(payment: Payment) {
        when (payment.kind) {
            Payment.KIND_SUBSCRIPTION ->
                subscriptionService.confirmSubscriptionPayment(payment.tier, requireNotNull(payment.subscriptionMonths))

            Payment.KIND_PACK -> organizerPack.confirmPack(requireNotNull(payment.subscriptionMonths), payment.guests ?: 0)

            Payment.KIND_PACK_EXTRA -> organizerPack.confirmExtra(payment.guests ?: 0)

            Payment.KIND_COMMISSION -> commission.activatePaid(requireNotNull(payment.commissionBatchId))

            else ->
                eventGuests.unlock(
                    requireNotNull(payment.eventId) { "A guests payment always references an event" },
                    requireNotNull(payment.guests) { "A guests payment always names its guests" },
                )
        }
    }
}
