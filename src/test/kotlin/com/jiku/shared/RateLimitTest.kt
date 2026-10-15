package com.jiku.shared

import com.jiku.shared.ratelimit.InMemoryRateLimiter
import com.jiku.shared.ratelimit.RateLimitFilter
import com.jiku.shared.ratelimit.RateLimitProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * JIKU-9B: the shared rate-limiting mechanism. The limiter's window arithmetic is
 * tested against a controllable clock; the filter's matching, keying and 429
 * contract are tested against mock requests.
 */
class RateLimitTest {
    private class SteppingClock(
        private var now: Instant,
    ) : Clock() {
        fun advance(duration: Duration) {
            now = now.plus(duration)
        }

        override fun instant(): Instant = now

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId): Clock = throw UnsupportedOperationException()
    }

    @Nested
    inner class Limiter {
        @Test
        fun `allows up to the budget and rejects beyond it with the time to reset`() {
            val clock = SteppingClock(Instant.parse("2026-07-03T10:00:00Z"))
            val limiter = InMemoryRateLimiter(clock)

            repeat(3) {
                assertThat(limiter.tryAcquire("key", 3, Duration.ofMinutes(1))).isNull()
            }
            clock.advance(Duration.ofSeconds(20))
            val retryAfter = limiter.tryAcquire("key", 3, Duration.ofMinutes(1))
            assertThat(retryAfter).isEqualTo(Duration.ofSeconds(40))
        }

        @Test
        fun `a fresh window resets the budget`() {
            val clock = SteppingClock(Instant.parse("2026-07-03T10:00:00Z"))
            val limiter = InMemoryRateLimiter(clock)

            repeat(4) { limiter.tryAcquire("key", 3, Duration.ofMinutes(1)) }
            clock.advance(Duration.ofSeconds(61))
            assertThat(limiter.tryAcquire("key", 3, Duration.ofMinutes(1))).isNull()
        }

        @Test
        fun `keys have independent budgets`() {
            val clock = SteppingClock(Instant.parse("2026-07-03T10:00:00Z"))
            val limiter = InMemoryRateLimiter(clock)

            repeat(4) { limiter.tryAcquire("busy", 3, Duration.ofMinutes(1)) }
            assertThat(limiter.tryAcquire("quiet", 3, Duration.ofMinutes(1))).isNull()
        }
    }

    @Nested
    inner class Filter {
        private val properties =
            RateLimitProperties(
                enabled = true,
                policies =
                    mapOf(
                        "rsvp" to
                            RateLimitProperties.Policy(
                                paths = listOf("/rsvp/**"),
                                maxRequests = 2,
                                window = Duration.ofMinutes(1),
                                tokenSegment = 1,
                            ),
                        "login" to
                            RateLimitProperties.Policy(
                                paths = listOf("/auth/login"),
                                maxRequests = 2,
                                window = Duration.ofMinutes(1),
                            ),
                    ),
            )

        private fun filter(secret: String = "") =
            RateLimitFilter(
                properties.copy(forwardedClientIpSecret = secret),
                ApiProperties(basePath = "/api/v1"),
                InMemoryRateLimiter(),
            )

        private fun fromWebServer(
            visitorIp: String,
            secret: String,
        ): MockHttpServletRequest =
            request("/api/v1/auth/login", ip = "198.51.100.7").apply {
                addHeader(RateLimitFilter.CLIENT_IP_HEADER, visitorIp)
                addHeader(RateLimitFilter.CLIENT_IP_SECRET_HEADER, secret)
            }

        private fun request(
            uri: String,
            ip: String = "203.0.113.10",
        ): MockHttpServletRequest =
            MockHttpServletRequest("POST", uri).apply {
                requestURI = uri
                remoteAddr = ip
            }

        private fun run(
            filter: RateLimitFilter,
            request: MockHttpServletRequest,
        ): MockHttpServletResponse {
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            return response
        }

        @Test
        fun `passes requests that match no policy`() {
            val filter = filter()
            repeat(10) {
                assertThat(run(filter, request("/api/v1/events")).status).isEqualTo(HttpStatus.OK.value())
            }
        }

        @Test
        fun `returns 429 with Retry-After and a clear message once the budget is spent`() {
            val filter = filter()
            repeat(2) {
                assertThat(run(filter, request("/api/v1/auth/login")).status).isEqualTo(HttpStatus.OK.value())
            }
            val rejected = run(filter, request("/api/v1/auth/login"))
            assertThat(rejected.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
            assertThat(rejected.getHeader("Retry-After")).isNotNull()
            assertThat(rejected.contentAsString).contains("Too many requests")
        }

        @Test
        fun `budgets are per client IP`() {
            val filter = filter()
            repeat(3) { run(filter, request("/api/v1/auth/login", ip = "203.0.113.10")) }
            assertThat(run(filter, request("/api/v1/auth/login", ip = "203.0.113.99")).status)
                .isEqualTo(HttpStatus.OK.value())
        }

        @Test
        fun `token flows key on the token as well, so each link has its own budget`() {
            val filter = filter()
            repeat(3) { run(filter, request("/api/v1/rsvp/token-a/confirm")) }
            assertThat(run(filter, request("/api/v1/rsvp/token-a/confirm")).status)
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
            assertThat(run(filter, request("/api/v1/rsvp/token-b/confirm")).status)
                .isEqualTo(HttpStatus.OK.value())
        }

        @Test
        fun `visitors behind the web server keep their own budget when it proves itself`() {
            val filter = filter(secret = "shared-secret")
            repeat(3) { run(filter, fromWebServer("203.0.113.10", "shared-secret")) }
            assertThat(run(filter, fromWebServer("203.0.113.10", "shared-secret")).status)
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
            assertThat(run(filter, fromWebServer("203.0.113.99", "shared-secret")).status)
                .isEqualTo(HttpStatus.OK.value())
        }

        @Test
        fun `a forwarded IP without the right secret is ignored`() {
            val filter = filter(secret = "shared-secret")
            repeat(2) { run(filter, fromWebServer("203.0.113.${it + 1}", "guessed")) }
            assertThat(run(filter, fromWebServer("203.0.113.50", "guessed")).status)
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
        }

        @Test
        fun `the header is ignored when no secret is configured`() {
            val filter = filter()
            repeat(2) { run(filter, fromWebServer("203.0.113.${it + 1}", "")) }
            assertThat(run(filter, fromWebServer("203.0.113.50", "")).status)
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value())
        }

        @Test
        fun `requests outside the API base path are never limited`() {
            val filter = filter()
            repeat(10) {
                assertThat(run(filter, request("/actuator/health")).status).isEqualTo(HttpStatus.OK.value())
            }
        }
    }
}
