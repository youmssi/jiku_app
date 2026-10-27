package com.jiku.money.internal

import org.springframework.stereotype.Component

/**
 * Chooses which [PaymentProvider] adapter handles a payment (ADR 104, §5). The
 * billing flow never names a provider: it asks for the [active] one when a
 * payment starts, and for the one recorded on the payment when its callback
 * arrives — so switching provider in configuration never strands the payments
 * already started with the previous one. Adding a provider means registering
 * another adapter bean and naming it in `billing.payment.provider`, or in
 * `billing.payment.by-currency` for the currencies it collects (JIKU-272).
 *
 * Startup fails when the configured name matches no adapter, or when two
 * adapters share a name: a misconfigured provider must never fall back silently
 * to another one.
 */
@Component
class PaymentProviderSelector(
    providers: List<PaymentProvider>,
    properties: PaymentProperties,
) {
    private val providersByName: Map<String, PaymentProvider> =
        providers.groupBy { it.name }.let { byName ->
            val duplicated = byName.filterValues { it.size > 1 }.keys
            check(duplicated.isEmpty()) { "Payment provider names must be unique; duplicated: ${duplicated.sorted()}" }
            byName.mapValues { it.value.single() }
        }

    /** The adapter that starts every new payment. */
    val active: PaymentProvider =
        checkNotNull(providersByName[properties.provider]) {
            "billing.payment.provider '${properties.provider}' matches no registered adapter; " +
                "registered: ${providersByName.keys.sorted()}"
        }

    private val providersByCurrency: Map<String, PaymentProvider?> =
        properties.byCurrency
            .mapKeys { it.key.uppercase() }
            .mapValues { it.value.trim() }
            .filterValues { it.isNotEmpty() }
            .mapValues { (currency, name) ->
                if (name == NO_ONLINE_PAYMENT) {
                    null
                } else {
                    checkNotNull(providersByName[name]) {
                        "billing.payment.by-currency.$currency '$name' matches no registered adapter; " +
                            "registered: ${providersByName.keys.sorted()}"
                    }
                }
            }

    /**
     * The adapter that starts a payment in [currency]: the one configured for it,
     * else [active]. `null` when that currency is paid by transfer only.
     */
    fun forCurrency(currency: String): PaymentProvider? {
        val key = currency.uppercase()
        return if (key in providersByCurrency) providersByCurrency[key] else active
    }

    /** The adapter registered under [name], or `null` when none is. */
    fun byName(name: String): PaymentProvider? = providersByName[name]
}
