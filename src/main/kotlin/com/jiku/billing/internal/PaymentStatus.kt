package com.jiku.billing.internal

/**
 * Lifecycle of a payment. A payment only unlocks a tier once it reaches
 * [SUCCEEDED] via the provider's server-to-server confirmation; a [FAILED] or
 * still-[PENDING] payment never changes the event's allowance.
 */
enum class PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
}
