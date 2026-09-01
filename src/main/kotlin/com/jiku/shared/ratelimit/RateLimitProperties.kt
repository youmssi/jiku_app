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
    /**
     * Where counters live. [Store.MEMORY] is per-instance and multiplies every
     * budget by the number of running instances, so [Store.DATABASE] is mandatory
     * before the API scales beyond one.
     */
    val store: Store = Store.MEMORY,
    /** Named policies, matched against the request path relative to the API base path. */
    val policies: Map<String, Policy> = emptyMap(),
) {
    enum class Store {
        /** Per-instance counters held in a map; no database round trip. */
        MEMORY,

        /** Counters shared through the `rate_limit_counter` table. */
        DATABASE,
    }

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
