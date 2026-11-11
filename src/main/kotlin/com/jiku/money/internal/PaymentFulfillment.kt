package com.jiku.money.internal

import com.jiku.shared.TenantContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/** A payment's purchase was granted; raised inside the confirming transaction (JIKU-277). */
data class PaymentFulfilled(
    val paymentId: UUID,
    val tenantId: String,
)

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
    private val events: ApplicationEventPublisher,
) {
    /**
     * Grants what [payment] paid for, then announces it so its invoice is issued
     * once the grant commits. Runs in the caller's transaction, under the
     * payment's tenant.
     */
    fun fulfill(payment: Payment) {
        grant(payment)
        events.publishEvent(PaymentFulfilled(requireNotNull(payment.id), requireNotNull(TenantContext.get())))
    }

    private fun grant(payment: Payment) {
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
