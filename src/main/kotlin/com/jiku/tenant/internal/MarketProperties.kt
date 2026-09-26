package com.jiku.tenant.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Currency
import java.util.Locale

/**
 * Countries where organizations can sign up (JIKU-107). An organization's
 * currency is its country's legal currency, so opening a market is a
 * configuration change.
 */
@ConfigurationProperties(prefix = "tenant.market")
data class MarketProperties(
    /** ISO 3166-1 alpha-2 country used when a sign-up names none. */
    val defaultCountry: String = "GN",
    val supportedCountries: Set<String> = setOf("GN"),
) {
    init {
        check(defaultCountry in supportedCountries) {
            "tenant.market.default-country '$defaultCountry' is not in tenant.market.supported-countries $supportedCountries"
        }
        // Fails at startup on a code that names no country with a currency.
        supportedCountries.forEach { Currency.getInstance(Locale.of("", it)) }
    }

    /** The market for [country] (the default one when blank), or null when Jikū is not sold there. */
    fun marketOf(country: String?): Market? {
        val code = country?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: defaultCountry
        if (code !in supportedCountries) return null
        return Market(country = code, currency = Currency.getInstance(Locale.of("", code)).currencyCode)
    }
}

data class Market(
    val country: String,
    val currency: String,
)
