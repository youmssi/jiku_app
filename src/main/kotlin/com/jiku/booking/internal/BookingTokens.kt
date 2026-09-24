package com.jiku.booking.internal

import java.security.MessageDigest

/**
 * Hashes the access token of a booking's public status/payment-declaration pages
 * (JIKU-55): the prospect has no account, so the token substitutes for a session.
 * Only the hash is persisted; the raw value lives solely in the link handed out
 * when the reservation was opened.
 */
internal object BookingTokens {
    fun hash(raw: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
