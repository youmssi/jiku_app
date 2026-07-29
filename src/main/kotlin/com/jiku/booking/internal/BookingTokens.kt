package com.jiku.booking.internal

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Random access token for a booking's public status/payment-declaration pages
 * (JIKU-55) — the prospect has no account yet, so this substitutes for a
 * session. Only the hash is persisted; the raw value exists solely in the URL
 * returned once at booking creation, mirroring the tenant module's account
 * tokens (kept as a separate copy, not a cross-module import, per the module
 * boundary rules).
 */
internal object BookingTokens {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
