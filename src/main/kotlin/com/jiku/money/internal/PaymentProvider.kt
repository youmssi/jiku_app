package com.jiku.money.internal

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
     * `null` when the payload cannot be trusted (bad or missing signature, or a
     * transaction the provider does not know), so the webhook can reject it.
     * Throws [PaymentProviderException] when the provider cannot be reached to
     * verify it. Never unlocks anything itself — that is the service's job once
     * this confirms authenticity.
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

/** Where a payment stands according to its provider. */
enum class PaymentOutcome {
    SUCCEEDED,
    FAILED,

    /** Not final yet (e.g. the payer has not validated on their phone): nothing changes. */
    PENDING,
}

data class PaymentCallback(
    /** The reference we supplied at initiation (tenant + payment id). */
    val reference: String,
    val providerReference: String,
    val outcome: PaymentOutcome,
    /** Amount the provider reports as paid, in minor units, when it reports one. */
    val amountMinor: Long? = null,
    /** Currency the provider reports, when it reports one. */
    val currency: String? = null,
)

/** The provider refused a request or could not be reached. */
class PaymentProviderException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
