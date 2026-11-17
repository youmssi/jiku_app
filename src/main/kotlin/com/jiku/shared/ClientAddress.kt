package com.jiku.shared

import com.jiku.shared.ratelimit.RateLimitProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * The visitor's IP address. Calls reach the API through the web server, so the
 * address is the one the web server forwards for its visitor when it proves
 * itself with the shared secret; the connection's address otherwise. Used by
 * the rate limits and by visit counts that must tell visitors apart (JIKU-189).
 */
@Component
class ClientAddress(
    private val properties: RateLimitProperties,
) {
    fun of(request: HttpServletRequest): String {
        val secret = properties.forwardedClientIpSecret
        if (secret.isBlank()) return request.remoteAddr
        val presented = request.getHeader(SECRET_HEADER) ?: return request.remoteAddr
        if (!MessageDigest.isEqual(presented.toByteArray(), secret.toByteArray())) return request.remoteAddr
        val forwarded = request.getHeader(HEADER)?.trim()
        return forwarded?.takeIf { IP_LITERAL.matches(it) } ?: request.remoteAddr
    }

    companion object {
        const val HEADER = "X-Jiku-Client-Ip"
        const val SECRET_HEADER = "X-Jiku-Client-Ip-Secret"

        /** An IPv4 or IPv6 literal: digits, hex letters, dots and colons, nothing else. */
        private val IP_LITERAL = Regex("^[0-9A-Fa-f:.]{2,45}$")
    }
}
