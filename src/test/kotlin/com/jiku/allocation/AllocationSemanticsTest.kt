package com.jiku.allocation

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 1 de l'ADR 102 : le module allocation est écrit et testé à côté, sans
 * consommateur métier. Ces tests cimentent la sémantique que les modules
 * propriétaires adapteront (Event/TicketType pour les places, money/ticket pour
 * les compteurs) avec leurs propres tests de concurrence inchangés.
 */
class AllocationSemanticsTest {
    @Test
    fun `a seat is taken only while under the limit`() {
        assertTrue(SeatAllocator.isOpen(0, 1))
        assertFalse(SeatAllocator.isOpen(1, 1))
        assertTrue(SeatAllocator.isOpen(1, 2))
        assertFalse(SeatAllocator.isOpen(2, 2))
        assertTrue(SeatAllocator.isOpen(5, null))
    }

    @Test
    fun `take returns the new count to write and never exceeds the limit`() {
        assertEquals(SeatAllocator.Seat(true, 2), SeatAllocator.take(1, 2))
        assertEquals(SeatAllocator.Seat(false, 2), SeatAllocator.take(2, 2))
        assertEquals(SeatAllocator.Seat(true, 6), SeatAllocator.take(5, null))
    }

    @Test
    fun `an unbounded seat is always taken`() {
        assertTrue(SeatAllocator.take(Long.MAX_VALUE - 1, null).taken)
    }

    @Test
    fun `counter hands out gapless numbers and keeps the row after creation`() {
        val store = InMemoryStore()
        val counter = AtomicCounter(store)
        assertEquals(1, counter.next())
        assertEquals(2, counter.next())
        assertEquals(3, counter.next())
        assertEquals(1, store.createdRows) // created once, reused afterwards
    }

    @Test
    fun `counter hands out distinct gapless numbers under concurrency`() {
        val store = InMemoryStore()
        val counter = AtomicCounter(store)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val gate = CountDownLatch(threads)
        val values = java.util.concurrent.ConcurrentSkipListSet<Long>()
        try {
            repeat(threads) {
                pool.execute {
                    gate.countDown()
                    start.await()
                    values.add(counter.next())
                }
            }
            assertTrue(gate.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals((1L..threads.toLong()).toList(), values.toList())
    }

    /**
     * Doublure en mémoire du port : incrément synchronisé, « création » idempotente.
     * La vraie atomicité (verrou pessimiste tenu jusqu'au commit) sera fournie par
     * les modules propriétaires derrière le port — ici on cimente le contrat.
     */
    private class InMemoryStore : AtomicCounter.CounterStore {
        private val next = AtomicLong(0)
        var createdRows: Int = 0
            private set

        override fun ensureRow() {
            synchronized(this) {
                if (createdRows == 0) createdRows++
            }
        }

        override fun nextValue(): Long = synchronized(this) { next.incrementAndGet() }
    }
}
