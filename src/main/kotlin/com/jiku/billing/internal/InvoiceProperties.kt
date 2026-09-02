package com.jiku.billing.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/**
 * Invoicing configuration (JIKU-69): who the seller is, and how consumption tax is
 * treated in each country the platform bills into.
 *
 * **No rate is hardcoded and none has a non-zero default.** The GTM plan records
 * Guinea's rate as unconfirmed and warns explicitly that Cameroon's should not be
 * planned against an assumed figure — so shipping a guess here would put a wrong
 * number on a legal document. An unconfigured country bills at zero tax with no
 * tax line, which is visibly wrong to whoever configures it rather than quietly
 * wrong on a customer's invoice.
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
