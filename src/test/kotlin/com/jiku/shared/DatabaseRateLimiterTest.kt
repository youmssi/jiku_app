package com.jiku.shared

import com.jiku.TestcontainersConfiguration
import com.jiku.shared.ratelimit.DatabaseRateLimiter
import com.jiku.shared.ratelimit.RateLimitCounterRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

/**
 * JIKU-79: the shared-store limiter, which closes boundary B10. The property that
 * matters is not that a budget is enforced — the in-memory limiter already does
 * that — but that **separate limiter instances enforce one shared budget**, since
 * that is exactly what the per-instance map fails to do once the API scales out.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DatabaseRateLimiterTest {
    @Autowired
    lateinit var counters: RateLimitCounterRepository

    private class FixedClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException()
    }

    private fun key() = "test:${UUID.randomUUID()}"

    @Test
    fun `two instances share one budget`() {
        val clock = FixedClock(Instant.parse("2026-09-01T10:00:00Z"))
        val instanceA = DatabaseRateLimiter(counters, clock)
        val instanceB = DatabaseRateLimiter(counters, clock)
        val key = key()

        // A budget of three, spent alternately across the two "replicas".
        assertThat(instanceA.tryAcquire(key, 3, Duration.ofMinutes(1))).isNull()
        assertThat(instanceB.tryAcquire(key, 3, Duration.ofMinutes(1))).isNull()
        assertThat(instanceA.tryAcquire(key, 3, Duration.ofMinutes(1))).isNull()

        // The fourth is refused by whichever instance sees it — the budget is shared,
        // not one budget per instance.
        assertThat(instanceB.tryAcquire(key, 3, Duration.ofMinutes(1))).isNotNull()
        assertThat(instanceA.tryAcquire(key, 3, Duration.ofMinutes(1))).isNotNull()
    }

    @Test
    fun `the window rolls over and restores the budget`() {
        val clock = FixedClock(Instant.parse("2026-09-01T10:00:00Z"))
        val limiter = DatabaseRateLimiter(counters, clock)
        val key = key()

        assertThat(limiter.tryAcquire(key, 1, Duration.ofMinutes(1))).isNull()
        val retryAfter = limiter.tryAcquire(key, 1, Duration.ofMinutes(1))
        assertThat(retryAfter).isNotNull()
        assertThat(retryAfter!!.seconds).isBetween(1L, 60L)

        clock.advance(Duration.ofMinutes(1).plusSeconds(1))
        assertThat(limiter.tryAcquire(key, 1, Duration.ofMinutes(1))).isNull()
    }

    @Test
    fun `concurrent instances never grant more than the budget`() {
        val clock = FixedClock(Instant.parse("2026-09-01T10:00:00Z"))
        val key = key()
        val budget = 5
        val attempts = 20

        val executor = Executors.newFixedThreadPool(8)
        try {
            val allowed =
                (1..attempts)
                    .map {
                        executor.submit<Boolean> {
                            // A fresh limiter per call: every attempt behaves like a
                            // different instance holding no local state of its own.
                            DatabaseRateLimiter(counters, clock).tryAcquire(key, budget, Duration.ofMinutes(1)) == null
                        }
                    }.map { it.get() }
                    .count { it }

            assertThat(allowed).isEqualTo(budget)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `the sweep removes only elapsed windows`() {
        val clock = FixedClock(Instant.parse("2026-09-01T10:00:00Z"))
        val limiter = DatabaseRateLimiter(counters, clock)
        val elapsed = key()
        val live = key()

        limiter.tryAcquire(elapsed, 5, Duration.ofMinutes(1))
        limiter.tryAcquire(live, 5, Duration.ofHours(2))

        counters.deleteExpired(clock.instant().plus(Duration.ofMinutes(5)))

        assertThat(counters.findById(elapsed)).isEmpty()
        assertThat(counters.findById(live)).isPresent()
    }
}
