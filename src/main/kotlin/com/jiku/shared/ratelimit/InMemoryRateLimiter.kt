package com.jiku.shared.ratelimit

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-instance, in-memory fixed-window counter. Correct only while the API runs as
 * a single instance: each instance keeps its own map, so N instances allow N times
 * every configured budget. That is why it is not the right choice once the API
 * scales out — see [DatabaseRateLimiter], selected with `api.rate-limit.store`.
 *
 * Kept as the default because it needs no database round trip on the hot path and
 * is exactly right for local development, the test suite and the current
 * single-instance deployment.
 *
 * Expired counters are swept periodically so abandoned keys (one-off guests,
 * scanning attempts) do not accumulate without bound.
 */
class InMemoryRateLimiter(
    private val clock: Clock = Clock.systemUTC(),
) : RateLimiter {
    private class Counter(
        val windowStart: Instant,
        val expiresAt: Instant,
        var requests: Int,
    )

    private val counters = ConcurrentHashMap<String, Counter>()
    private val acquisitions = AtomicLong()

    override fun tryAcquire(
        key: String,
        maxRequests: Int,
        window: Duration,
    ): Duration? {
        val now = clock.instant()
        maybeSweep(now)
        val counter =
            counters.compute(key) { _, existing ->
                if (existing == null || now.isAfter(existing.expiresAt)) {
                    Counter(now, now.plus(window), 1)
                } else {
                    existing.requests++
                    existing
                }
            }!!
        if (counter.requests <= maxRequests) {
            return null
        }
        return Duration.between(now, counter.expiresAt).coerceAtLeast(Duration.ofSeconds(1))
    }

    private fun maybeSweep(now: Instant) {
        if (acquisitions.incrementAndGet() % SWEEP_EVERY != 0L) {
            return
        }
        counters.entries.removeIf { now.isAfter(it.value.expiresAt) }
    }

    private companion object {
        const val SWEEP_EVERY = 4096L
    }
}
