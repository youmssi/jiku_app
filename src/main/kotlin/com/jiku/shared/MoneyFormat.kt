package com.jiku.shared

import java.math.BigDecimal

/**
 * Rend un montant en unités mineures sous forme lisible.
 *
 * Le GNF, le XAF et le XOF n'ont **pas** d'unité mineure : leur diviser par cent,
 * comme on le ferait pour un euro, afficherait chaque montant guinéen cent fois
 * trop petit. C'est une erreur qui ne se découvre que sur la facture d'un client,
 * d'où son extraction ici plutôt qu'une réécriture à chaque nouveau document.
 */
object MoneyFormat {
    /** Devises ISO 4217 sans unité mineure — celle de la plateforme incluse. */
    private val ZERO_DECIMAL =
        setOf("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF")

    /** Si [currency] n'a pas d'unité mineure : le montant mineur est alors le montant entier. */
    fun isZeroDecimal(currency: String): Boolean = currency.uppercase() in ZERO_DECIMAL

    fun format(
        minor: Long,
        currency: String,
    ): String {
        val code = currency.uppercase()
        return if (isZeroDecimal(code)) {
            "%,d %s".format(minor, code)
        } else {
            "%,.2f %s".format(BigDecimal(minor).movePointLeft(2), code)
        }
    }
}
