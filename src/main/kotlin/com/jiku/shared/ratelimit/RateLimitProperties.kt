package com.jiku.shared.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Rate-limiting policies for endpoints reachable without an organizer login. Every
 * limit lives in configuration (see `application.yaml`, which documents the
 * rationale for each number) rather than in code, so an environment can tune a
 * policy without a release.
 */
@ConfigurationProperties(prefix = "api.rate-limit")
data class RateLimitProperties(
    /** Master switch. Disabled only in tests that intentionally flood endpoints. */
    val enabled: Boolean = true,
    /** Named policies, matched against the request path relative to the API base path. */
    val policies: Map<String, Policy> = emptyMap(),
) {
    data class Policy(
        /**
         * Ant-style patterns relative to the API base path (e.g. a `**` wildcard
         * under `/rsvp`). Patterns of different policies must not overlap: the
         * first matching policy wins and map order is not guaranteed.
         */
        val paths: List<String> = emptyList(),
        /** Requests allowed per [window] for one key. */
        val maxRequests: Int = 0,
        /** Fixed window length. */
        val window: Duration = Duration.ofMinutes(1),
        /**
         * When set, the path segment at this zero-based index (relative to the API
         * base path) joins the client IP in the limiter key. Used for the
         * invitation/validator token so each link gets its own budget: one abusive
         * link cannot exhaust the budget of legitimate clients behind a shared IP,
         * and one IP cannot scan many tokens for free.
         */
        val tokenSegment: Int? = null,
    )
}
