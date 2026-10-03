package com.jiku.shared.async

import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JIKU-215: volume work never refuses a task, and urgent work never waits behind it. */
class AsyncConfigTest {
    private val config = AsyncConfig(bulkThreads = 1, urgentConcurrency = 4)
    private val bulk = config.bulkExecutor()
    private val urgent = config.urgentExecutor()

    @AfterEach
    fun stop() {
        bulk.shutdown()
        TenantContext.clear()
    }

    @Test
    fun `volume work waits its turn instead of being refused`() {
        val done = CountDownLatch(500)

        repeat(500) { bulk.execute { done.countDown() } }

        assertTrue(done.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `urgent work runs on a virtual thread while every volume thread is busy`() {
        val release = CountDownLatch(1)
        bulk.execute { release.await(10, TimeUnit.SECONDS) }
        val ran = CountDownLatch(1)
        val virtual = AtomicBoolean(false)

        urgent.execute {
            virtual.set(Thread.currentThread().isVirtual)
            ran.countDown()
        }

        assertTrue(ran.await(2, TimeUnit.SECONDS), "urgent work waited behind volume work")
        assertTrue(virtual.get())
        release.countDown()
    }

    @Test
    fun `the organization of the caller follows the task`() {
        TenantContext.set("tenant-async")
        val seen = AtomicReference<String?>()
        val ran = CountDownLatch(1)

        urgent.execute {
            seen.set(TenantContext.get())
            ran.countDown()
        }

        assertTrue(ran.await(2, TimeUnit.SECONDS))
        assertEquals("tenant-async", seen.get())
    }
}
