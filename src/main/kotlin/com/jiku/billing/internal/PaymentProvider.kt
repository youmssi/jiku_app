package com.jiku.billing.internal

import java.util.UUID

/**
 * Provider-agnostic Mobile Money integration point (JIKU-33). The billing flow
 * depends only on this SPI; the concrete provider (Orange Money, Wave, MTN MoMo,
 * or a regional aggregator) is a wiring decision that drops in as a bean without
 * touching the flow. A sandbox implementation ships by default so the end-to-end
 * flow works before a provider is chosen.
 */
interface PaymentProvider {
    /** Identifier recorded on each payment (e.g. "sandbox", "wave", "orange-money"). */
    val name: String

    /**
     * Starts a payment with the provider and returns how the organizer completes
     * it (a redirect, a USSD prompt, or a QR, per the provider's pattern) plus the
     * provider's own reference. The payment is not confirmed here — only the
     * provider's server-to-server callback confirms it.
     */
    fun initiate(request: PaymentInitiationRequest): PaymentInitiation

    /**
     * Verifies an inbound provider callback and extracts its outcome. Returns
     * `null` when the payload cannot be trusted (bad or missing signature), so the
     * webhook can reject it. Never unlocks anything itself — that is the service's
     * job once this confirms authenticity.
     */
    fun parseCallback(
        rawBody: String,
        signature: String?,
    ): PaymentCallback?
}

data class PaymentInitiationRequest(
    val paymentId: UUID,
    val amountMinor: Long,
    val currency: String,
    val description: String,
    /** Opaque reference echoed back by the provider on callback; encodes tenant + payment. */
    val reference: String,
)

data class PaymentInitiation(
    val providerReference: String,
    val instruction: PaymentInstruction,
)

/** How the organizer completes the payment. [type] is REDIRECT, USSD or QR. */
data class PaymentInstruction(
    val type: String,
    val value: String,
)

data class PaymentCallback(
    /** The reference we supplied at initiation (tenant + payment id). */
    val reference: String,
    val providerReference: String,
    val succeeded: Boolean,
)
