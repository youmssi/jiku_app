package com.jiku.money.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * Invoicing configuration (JIKU-69): who the seller is, and the consumption tax
 * included in prices in each country the platform bills into (JIKU-268). Rates
 * come from configuration, never code; a country without one bills at zero
 * tax with a zero tax line.
 */
@ConfigurationProperties(prefix = "billing.invoice")
data class InvoiceProperties(
    /** Prefix of the human-readable number, e.g. `INV-2026-000001`. */
    val numberPrefix: String = "INV",
    val creditNotePrefix: String = "CN",
    val seller: Seller = Seller(),
    /**
     * Zone whose calendar day dates an invoice. An invoice is dated by day in its
     * jurisdiction, not by UTC instant, so this is set to where the platform bills
     * from; near midnight the two differ.
     */
    val issueZone: String = "UTC",
    /** Keyed by ISO 3166-1 alpha-2 country code, upper case. */
    val tax: Map<String, TaxTreatment> = emptyMap(),
) {
    data class Seller(
        val name: String = "Jiku",
        val addressLine: String? = null,
        val taxIdentifier: String? = null,
    )

    data class TaxTreatment(
        /** Shown on the document, e.g. `TVA 19,25%`. */
        val label: String = "",
        /** Fraction, not a percentage: 0.1925 for 19.25%. */
        val rate: BigDecimal = BigDecimal.ZERO,
    )

    /** Zero-rated when a country has no configured treatment. */
    fun taxFor(country: String): TaxTreatment = tax[country.uppercase()] ?: TaxTreatment()
}
