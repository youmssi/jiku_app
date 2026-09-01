package com.jiku.shared.ratelimit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface RateLimitCounterRepository : JpaRepository<RateLimitCounter, String> {
    /**
     * Atomically rolls the window over when it has expired, increments the count,
     * and enforces the budget — all in one statement, so two concurrent instances
     * can never both be allowed past [maxRequests]. Returns the number of rows
     * changed: 1 when the request is allowed, 0 when the budget is exhausted.
     *
     * The `CASE` arms handle the two ways an existing row can be reached: an
     * expired window restarts at one, a live window increments. The `WHERE` clause
     * is what actually denies — it lets the update through only when the window has
     * expired (a fresh budget) or the live count is still under the ceiling.
     */
    @Modifying
    @Transactional
    @Query(
        value =
            "INSERT INTO rate_limit_counter (bucket_key, window_start, expires_at, request_count) " +
                "VALUES (:key, :now, :expiresAt, 1) " +
                "ON CONFLICT (bucket_key) DO UPDATE SET " +
                "request_count = CASE WHEN rate_limit_counter.expires_at <= :now THEN 1 " +
                "ELSE rate_limit_counter.request_count + 1 END, " +
                "window_start = CASE WHEN rate_limit_counter.expires_at <= :now THEN :now " +
                "ELSE rate_limit_counter.window_start END, " +
                "expires_at = CASE WHEN rate_limit_counter.expires_at <= :now THEN :expiresAt " +
                "ELSE rate_limit_counter.expires_at END " +
                "WHERE rate_limit_counter.expires_at <= :now " +
                "OR rate_limit_counter.request_count < :maxRequests",
        nativeQuery = true,
    )
    fun tryAcquire(
        @Param("key") key: String,
        @Param("now") now: Instant,
        @Param("expiresAt") expiresAt: Instant,
        @Param("maxRequests") maxRequests: Int,
    ): Int

    /** Removes windows that have already elapsed, so abandoned keys do not accumulate. */
    @Modifying
    @Transactional
    @Query("DELETE FROM RateLimitCounter c WHERE c.expiresAt <= :now")
    fun deleteExpired(
        @Param("now") now: Instant,
    ): Int
}
