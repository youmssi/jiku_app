package com.jiku.shared.ratelimit

import java.time.Duration

/**
 * Fixed-window request counter keyed by an arbitrary string. Two implementations
 * exist: [InMemoryRateLimiter], correct only while the API runs as a single
 * instance, and [DatabaseRateLimiter], which shares one budget across every
 * instance. Which one is active is a configuration decision (`api.rate-limit.store`),
 * not a code change.
 */
interface RateLimiter {
    /**
     * Records one request for [key] and returns `null` when it fits within
     * [maxRequests] per [window], or the time remaining until the window resets
     * when the budget is exhausted (suitable for a `Retry-After` header).
     */
    fun tryAcquire(
        key: String,
        maxRequests: Int,
        window: Duration,
    ): Duration?
}
