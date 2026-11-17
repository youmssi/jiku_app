package com.jiku.shared

import com.jiku.shared.ratelimit.RateLimiter
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Counts attempts at something guessable, such as a sign-in code (JIKU-317), in
 * the same store as the request rate limits, so the budget holds across every
 * instance of the API when that store is shared.
 */
@Component
class AttemptLimiter(
    private val limiter: RateLimiter,
) {
    /** Records one attempt for [key]; null while within [max] per [window], else the wait before the next. */
    fun tryAttempt(
        key: String,
        max: Int,
        window: Duration,
    ): Duration? = limiter.tryAcquire(key, max, window)
}
