package com.jiku.shared.ratelimit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One fixed window's request count for one limiter key (JIKU-79), shared by every
 * API instance. Written only through [RateLimitCounterRepository.tryAcquire]'s
 * conditional upsert, so two instances can never both be allowed past a budget.
 *
 * Not tenant-scoped: every rate-limited policy protects an endpoint reachable
 * without an organizer login, so there is no tenant context when the counter is
 * touched. [bucketKey] already carries the policy name, the client IP and — for
 * link flows — the token segment.
 */
@Entity
@Table(name = "rate_limit_counter")
class RateLimitCounter(
    @Id
    @Column(name = "bucket_key", nullable = false, updatable = false)
    val bucketKey: String,
    @Column(name = "window_start", nullable = false)
    val windowStart: Instant,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "request_count", nullable = false)
    val requestCount: Int,
)
