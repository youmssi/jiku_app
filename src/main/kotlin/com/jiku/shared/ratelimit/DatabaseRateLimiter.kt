package com.jiku.shared.ratelimit

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Fixed-window counter backed by a shared table, so one budget is enforced across
 * every API instance. Selected with `api.rate-limit.store=database`; required
 * before the API runs more than one instance, because [InMemoryRateLimiter] would
 * otherwise multiply every configured budget by the replica count.
 *
 * The allowed path is a single round trip: the conditional upsert rolls the window
 * over, increments and enforces the ceiling in one statement. Only the denied path
 * costs a second read, to report how long the caller must wait.
 *
 * **Fails open.** If the shared store cannot be reached the request is allowed and
 * a warning is logged, rather than every public endpoint returning 429 because a
 * counter is unavailable — that would turn a counter outage into a full outage.
 * The exposure is bounded: every endpoint behind these policies needs the same
 * database to do its work, so it would fail on its own moments later anyway.
 */
class DatabaseRateLimiter(
    private val counters: RateLimitCounterRepository,
    private val clock: Clock = Clock.systemUTC(),
) : RateLimiter {
    private val log = LoggerFactory.getLogger(DatabaseRateLimiter::class.java)

    override fun tryAcquire(
        key: String,
        maxRequests: Int,
        window: Duration,
    ): Duration? {
        val now = clock.instant()
        return try {
            if (counters.tryAcquire(key, now, now.plus(window), maxRequests) == 1) {
                null
            } else {
                retryAfter(key, now, window)
            }
        } catch (ex: Exception) {
            log.warn("Rate-limit store unavailable; allowing the request for key {}", key, ex)
            null
        }
    }

    /**
     * Time left in the window that just rejected the caller. The row is read back
     * rather than assumed, so the header reflects the window actually in force; if
     * it has since been swept, a full window is the safe over-estimate.
     */
    private fun retryAfter(
        key: String,
        now: Instant,
        window: Duration,
    ): Duration {
        val expiresAt = counters.findById(key).orElse(null)?.expiresAt ?: return window
        return Duration.between(now, expiresAt).coerceAtLeast(Duration.ofSeconds(1))
    }
}
