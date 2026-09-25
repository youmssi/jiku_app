package com.jiku.money

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * One price in the three currencies Jikū bills in (ADR 105): Guinean francs,
 * CFA francs (XOF and XAF carry the same amount) and US dollars, each a round
 * amount set by hand rather than converted. Amounts are in minor units: GNF and
 * FCFA have none, the dollar is in cents.
 */
data class PriceList(
    val gnf: Long,
    val fcfa: Long,
    val usdCents: Long,
) {
    fun amountMinor(billingCurrency: String): Long =
        when (billingCurrency) {
            GNF -> gnf
            XOF, XAF -> fcfa
            USD -> usdCents
            else -> throw IllegalArgumentException("Jikū does not bill in $billingCurrency")
        }

    @JsonIgnore
    fun isFree(): Boolean = gnf == 0L && fcfa == 0L && usdCents == 0L

    companion object {
        const val GNF = "GNF"
        const val XOF = "XOF"
        const val XAF = "XAF"
        const val USD = "USD"

        val FREE = PriceList(0, 0, 0)

        /**
         * The currency Jikū bills an organization in, from the currency of its
         * country: Guinean and CFA francs are billed as they are, every other
         * currency in US dollars.
         */
        fun billingCurrencyFor(organizationCurrency: String): String =
            organizationCurrency.uppercase().takeIf { it in setOf(GNF, XOF, XAF) } ?: USD
    }
}
