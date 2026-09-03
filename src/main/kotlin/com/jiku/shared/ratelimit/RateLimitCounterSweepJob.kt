package com.jiku.shared.ratelimit

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Removes elapsed rate-limit windows (JIKU-79). Every distinct client IP and link
 * token leaves a row behind, so without a sweep the table grows with traffic
 * rather than with concurrent users. Registered only when the shared store is in
 * use — with the in-memory store the table is never written.
 *
 * Every instance runs this; deleting an already-deleted expired row is harmless,
 * so no leader election is needed.
 */
@Component
@ConditionalOnProperty(prefix = "api.rate-limit", name = ["store"], havingValue = "database")
class RateLimitCounterSweepJob(
    private val counters: RateLimitCounterRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(RateLimitCounterSweepJob::class.java)

    @Scheduled(cron = "\${api.rate-limit.sweep-cron:0 */10 * * * *}")
    fun sweep() {
        val removed = counters.deleteExpired(clock.instant())
        if (removed > 0) {
            log.debug("Rate-limit sweep: removed {} elapsed window(s)", removed)
        }
    }
}
