package com.jiku.money.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Payment integration configuration (JIKU-33). The provider name, the webhook
 * signing secret, and the sandbox return URL are all environment configuration —
 * the secret ships only a development default and must be set explicitly outside
 * local.
 */
@ConfigurationProperties(prefix = "billing.payment")
data class PaymentProperties(
    /** Selects the active provider adapter; "sandbox" until a real one is wired in. */
    val provider: String = "sandbox",
    /** Shared secret used to verify the HMAC signature on provider callbacks. */
    val webhookSecret: String = "local-development-payment-webhook-secret",
    /** Where the sandbox provider tells the client to return after "paying". */
    val sandboxReturnUrl: String = "http://localhost:3000/billing/return",
)
