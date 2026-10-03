package com.jiku.messaging

import com.github.benmanes.caffeine.cache.Ticker
import com.jiku.messaging.internal.PerTenantCache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** JIKU-216: a batch asks the database once per organization and minute, not once per message. */
class PerTenantCacheTest {
    private val now = AtomicLong(0)
    private val ticker = Ticker { now.get() }
    private val loads = AtomicInteger(0)

    @Test
    fun `an answer is reused until it expires, per organization`() {
        val cache = PerTenantCache<String>(Duration.ofSeconds(60), ticker)

        repeat(300) { assertThat(cache.get("org-a") { load("paused") }).isEqualTo("paused") }
        assertThat(cache.get("org-b") { load("other") }).isEqualTo("other")
        assertThat(loads.get()).isEqualTo(2)

        now.addAndGet(Duration.ofSeconds(61).toNanos())
        assertThat(cache.get("org-a") { load("lifted") }).isEqualTo("lifted")
        assertThat(loads.get()).isEqualTo(3)
    }

    @Test
    fun `no answer is kept too, so an organization in good standing is not counted again`() {
        val cache = PerTenantCache<String>(Duration.ofSeconds(60), ticker)

        repeat(10) { assertThat(cache.get("org-a") { load(null) }).isNull() }
        assertThat(loads.get()).isEqualTo(1)
    }

    @Test
    fun `a zero duration asks every time`() {
        val cache = PerTenantCache<Boolean>(Duration.ZERO, ticker)

        repeat(3) { cache.get("org-a") { load(true) } }
        assertThat(loads.get()).isEqualTo(3)
    }

    private fun <V> load(value: V): V {
        loads.incrementAndGet()
        return value
    }
}
