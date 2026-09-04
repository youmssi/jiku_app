package com.jiku.catalog.internal

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Jeton de réservation client (JIKU-87) : une valeur aléatoire remise au client,
 * stockée uniquement hashée (SHA-256) sur les lignes de réservation, pour
 * consulter et annuler sans compte.
 */
object BookingToken {
    private val random = SecureRandom()

    fun new(): String {
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hash(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
