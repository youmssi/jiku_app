package com.jiku.tenant.internal

import com.jiku.shared.sha256Hex
import java.security.SecureRandom
import java.util.Base64

/**
 * Random single-use tokens mailed as links (JIKU-49/50). Only the SHA-256 hash
 * is ever persisted; the raw value exists solely in the emailed URL.
 */
internal object Tokens {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(raw: String): String = sha256Hex(raw)
}
