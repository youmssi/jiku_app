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
    private val ZERO_DECIMAL = setOf("GNF", "XAF", "XOF", "JPY", "KRW", "RWF", "UGX", "VND")

    fun format(
        minor: Long,
        currency: String,
    ): String {
        val code = currency.uppercase()
        return if (code in ZERO_DECIMAL) {
            "%,d %s".format(minor, code)
        } else {
            "%,.2f %s".format(BigDecimal(minor).movePointLeft(2), code)
        }
    }
}
