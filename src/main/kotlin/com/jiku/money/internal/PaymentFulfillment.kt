package com.jiku.money.internal

import org.springframework.stereotype.Component

/**
 * What a succeeded payment grants, by kind (JIKU-164): an event tier, months of a
 * services plan or of the Organizer Pack, extra pack guests, or months of the own
 * WhatsApp number add-on. The one place both confirmation paths go through — an
 * admin confirming a manual transfer and a provider confirming an online payment —
 * so a kind is never granted differently depending on how it was paid.
 */
@Component
class PaymentFulfillment(
    private val tierUnlockService: TierUnlockService,
    private val subscriptionService: SubscriptionService,
    private val organizerPack: OrganizerPackService,
    private val ownWhatsAppNumber: OwnWhatsAppNumberService,
) {
    /** Grants what [payment] paid for. Runs in the caller's transaction, under the payment's tenant. */
    fun fulfill(payment: Payment) {
        when (payment.kind) {
            Payment.KIND_SUBSCRIPTION ->
                subscriptionService.confirmSubscriptionPayment(payment.tier, requireNotNull(payment.subscriptionMonths))

            Payment.KIND_PACK -> organizerPack.confirmPack(requireNotNull(payment.subscriptionMonths), payment.guests ?: 0)

            Payment.KIND_PACK_EXTRA -> organizerPack.confirmExtra(payment.guests ?: 0)

            Payment.KIND_WHATSAPP_NUMBER -> ownWhatsAppNumber.confirm(requireNotNull(payment.subscriptionMonths))

            else ->
                tierUnlockService.unlock(
                    requireNotNull(payment.eventId) { "A tier payment always references an event" },
                    payment.tier,
                    payment.interactive,
                )
        }
    }
}
