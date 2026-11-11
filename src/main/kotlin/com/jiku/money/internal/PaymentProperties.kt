package com.jiku.money.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Payment integration configuration (JIKU-33). The provider name, the webhook
 * signing secret, and the sandbox return URL are all environment configuration —
 * the secret ships only a development default and must be set explicitly outside
 * local.
 */
@ConfigurationProperties(prefix = "billing.payment")
data class PaymentProperties(
    /** Name of the adapter that starts new payments, resolved by [PaymentProviderSelector]. */
    val provider: String = "sandbox",
    /**
     * The adapter per currency (JIKU-272), e.g. `GNF` to `dexpay`; a currency left
     * out or blank uses [provider], and [NO_ONLINE_PAYMENT] offers none, so the
     * organization pays that currency by transfer.
     */
    val byCurrency: Map<String, String> = emptyMap(),
    /** How long a payment waits for its callback before its provider is asked directly (JIKU-272). */
    val reconcileAfter: Duration = Duration.ofMinutes(2),
    /** A payment its provider still reports as unfinished after this long is closed as FAILED. */
    val abandonAfter: Duration = Duration.ofDays(2),
    /** Shared secret used to verify the HMAC signature on provider callbacks. */
    val webhookSecret: String = "local-development-payment-webhook-secret",
    /** Where the sandbox provider tells the client to return after "paying". */
    val sandboxReturnUrl: String = "http://localhost:3000/billing/return",
)

/** The [PaymentProperties.byCurrency] value that offers no online payment in a currency. */
const val NO_ONLINE_PAYMENT = "none"
