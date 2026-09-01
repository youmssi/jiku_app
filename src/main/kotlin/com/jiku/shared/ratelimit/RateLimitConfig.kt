package com.jiku.shared.ratelimit

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Chooses the rate-limit store. `memory` (the default) keeps the per-instance
 * counter, which needs no database round trip and is right for local development,
 * the test suite and a single-instance deployment. `database` shares one budget
 * across every instance and **must** be selected before the API runs more than
 * one, otherwise each instance grants the full budget independently.
 */
@Configuration
class RateLimitConfig {
    @Bean
    fun rateLimiter(
        properties: RateLimitProperties,
        counters: RateLimitCounterRepository,
    ): RateLimiter =
        when (properties.store) {
            RateLimitProperties.Store.DATABASE -> DatabaseRateLimiter(counters, Clock.systemUTC())
            RateLimitProperties.Store.MEMORY -> InMemoryRateLimiter(Clock.systemUTC())
        }
}
