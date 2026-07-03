package com.jiku.shared.ratelimit

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory, fixed-window request counter keyed by an arbitrary string. In-memory
 * and per-instance by design at MVP scale (a single API replica); if the API ever
 * runs behind more than one instance, back this with a shared store instead of
 * scaling the map.
 *
 * Expired counters are swept periodically so abandoned keys (one-off guests,
 * scanning attempts) do not accumulate without bound.
 */
@Component
class FixedWindowRateLimiter(
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Counter(
        val windowStart: Instant,
        val expiresAt: Instant,
        var requests: Int,
    )

    private val counters = ConcurrentHashMap<String, Counter>()
    private val acquisitions = AtomicLong()

    /**
     * Records one request for [key] and returns `null` when it fits within
     * [maxRequests] per [window], or the time remaining until the window resets
     * when the budget is exhausted (suitable for a `Retry-After` header).
     */
    fun tryAcquire(
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
