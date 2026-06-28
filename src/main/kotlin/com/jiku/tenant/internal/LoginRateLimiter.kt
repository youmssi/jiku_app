package com.jiku.tenant.internal

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, per-IP fixed-window throttle for the login endpoint, to blunt
 * brute-force attempts. Single-instance only by design — JIKU-9B generalizes this
 * into the shared rate-limiting mechanism backing all public endpoints.
 */
@Component
class LoginRateLimiter(
    private val properties: LoginRateLimitProperties,
) {
    private class Counter(
        val windowStart: Instant,
        var attempts: Int,
    )

    private val counters = ConcurrentHashMap<String, Counter>()

    fun recordAttempt(clientIp: String) {
        val now = Instant.now()
        val counter =
            counters.compute(clientIp) { _, existing ->
                if (existing == null || now.isAfter(existing.windowStart.plus(properties.window))) {
                    Counter(now, 1)
                } else {
                    existing.attempts++
                    existing
                }
            }!!
        if (counter.attempts > properties.maxAttempts) {
            throw ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many login attempts; please try again later",
            )
        }
    }
}
