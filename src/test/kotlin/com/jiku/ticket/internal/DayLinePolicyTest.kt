package com.jiku.ticket.internal

import com.jiku.support.TestDates.MONDAY
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * La règle « suivant » (§4.1) sur les quatre cas : un rendez-vous en cours et
 * arrivé prime ; un rendez-vous non arrivé ne bloque pas ; passé la tolérance un
 * rendez-vous perd sa priorité et rejoint la file ; à défaut, la plus longue
 * attente l'emporte. (JIKU-88)
 */
class DayLinePolicyTest {
    private val now = Instant.parse("${MONDAY}T10:00:00Z")

    private fun candidate(
        slotStart: Instant?,
        arrivedAt: Instant?,
        kind: TicketKind = TicketKind.APPOINTMENT,
        status: TicketStatus = TicketStatus.WAITING,
    ) = LineCandidate(UUID.randomUUID(), kind, status, slotStart, arrivedAt)

    private fun walkIn(arrivedAt: Instant) = candidate(null, arrivedAt, kind = TicketKind.WALK_IN)

    @Test
    fun `an arrived appointment whose slot is running wins over an earlier walk-in`() {
        val slot = now
        val earlier = walkIn(now.minusSeconds(30 * 60))
        val inSlot = candidate(slot, now.minusSeconds(2 * 60))

        val next = DayLinePolicy.nextToCall(listOf(earlier, inSlot), now, toleranceMinutes = 10)

        assertEquals(inSlot.ticketId, assertNotNull(next).ticketId)
    }

    @Test
    fun `a not-yet-arrived appointment never blocks the walk-in with the longest wait`() {
        val slot = now
        val notArrived = candidate(slot, null, status = TicketStatus.ISSUED)
        val earlier = walkIn(now.minusSeconds(20 * 60))

        val next = DayLinePolicy.nextToCall(listOf(notArrived, earlier), now, toleranceMinutes = 10)

        assertEquals(earlier.ticketId, assertNotNull(next).ticketId)
    }

    @Test
    fun `past the tolerance an arrived appointment loses priority and joins the queue by arrival`() {
        val lateSlot = now.minusSeconds(15 * 60)
        val lateArrival = candidate(lateSlot, now.minusSeconds(6 * 60))
        val earlierArrival = walkIn(now.minusSeconds(10 * 60))

        val next = DayLinePolicy.nextToCall(listOf(lateArrival, earlierArrival), now, toleranceMinutes = 10)

        assertEquals(earlierArrival.ticketId, assertNotNull(next).ticketId)
    }

    @Test
    fun `with no running slot the longest wait wins whatever the kind`() {
        val futureSlot = now.plusSeconds(2 * 60 * 60)
        val earlyBird = candidate(futureSlot, now.minusSeconds(40 * 60))
        val laterWalkIn = walkIn(now.minusSeconds(5 * 60))

        val next = DayLinePolicy.nextToCall(listOf(earlyBird, laterWalkIn), now, toleranceMinutes = 10)

        assertEquals(earlyBird.ticketId, assertNotNull(next).ticketId)
    }

    @Test
    fun `nobody waiting means nobody to call`() {
        val next = DayLinePolicy.nextToCall(emptyList(), now, toleranceMinutes = 10)
        assertNull(next)
    }
}
