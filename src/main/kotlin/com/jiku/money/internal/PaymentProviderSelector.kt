package com.jiku.money.internal

import org.springframework.stereotype.Component

/**
 * Chooses which [PaymentProvider] adapter handles a payment (ADR 104, §5). The
 * billing flow never names a provider: it asks for the [active] one when a
 * payment starts, and for the one recorded on the payment when its callback
 * arrives — so switching provider in configuration never strands the payments
 * already started with the previous one. Adding a provider means registering
 * another adapter bean and naming it in `billing.payment.provider`.
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

    /** The adapter registered under [name], or `null` when none is. */
    fun byName(name: String): PaymentProvider? = providersByName[name]
}
