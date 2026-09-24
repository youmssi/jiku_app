package com.jiku.shared

import java.security.SecureRandom

/**
 * Short, unambiguous random codes for shareable links (JIKU-56/88): the
 * alphabet omits 0/O/1/I/L so a code read aloud or copied from a phone never
 * gets confused. Callers own their own collision retry against their table's
 * unique constraint — generation itself never checks uniqueness.
 */
object RandomCode {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generate(length: Int): String = (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}
