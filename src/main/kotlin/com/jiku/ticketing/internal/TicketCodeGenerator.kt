package com.jiku.ticketing.internal

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates cryptographically random, non-sequential, URL-safe ticket codes
 * (120 bits of entropy) so a code cannot be guessed or enumerated.
 */
@Component
class TicketCodeGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String {
        val bytes = ByteArray(15)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}
