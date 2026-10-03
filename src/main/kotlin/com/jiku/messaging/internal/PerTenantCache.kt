package com.jiku.messaging.internal

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import java.time.Duration
import java.util.Optional

/**
 * Keeps, for [ttl], an answer about one organization that every send asks for
 * and that changes slowly: is it paused, is it verified (JIKU-216). A batch of
 * 300 invitations then asks the database once instead of 300 times. A zero
 * [ttl] turns the cache off.
 */
class PerTenantCache<V : Any>(
    private val ttl: Duration,
    ticker: Ticker = Ticker.systemTicker(),
) {
    private val entries: Cache<String, Optional<V>> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(MAX_ORGANIZATIONS)
            .ticker(ticker)
            .build()

    fun get(
        tenantId: String,
        load: () -> V?,
    ): V? {
        if (ttl.isZero) return load()
        return entries.get(tenantId) { Optional.ofNullable(load()) }.orElse(null)
    }

    private companion object {
        const val MAX_ORGANIZATIONS = 10_000L
    }
}
