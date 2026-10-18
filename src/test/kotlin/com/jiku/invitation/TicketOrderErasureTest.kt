package com.jiku.invitation

import com.jiku.invitation.internal.TicketOrder
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JIKU-267: an order past retention loses its buyer's details, not its reference or amount. */
class TicketOrderErasureTest {
    @Test
    fun `erasing an order removes the buyer and keeps the accounts`() {
        val order =
            TicketOrder(
                eventId = UUID.randomUUID(),
                reference = "JK-ABC123",
                buyerName = "Mariama Bah",
                buyerPhone = "+224620000001",
                buyerEmail = "mariama@example.com",
                totalMinor = 150_000,
                currency = "GNF",
                expiresAt = Instant.now(),
            ).apply { id = UUID.randomUUID() }
        assertFalse(order.erased)

        order.erase()

        assertTrue(order.erased)
        assertEquals("Deleted Buyer", order.buyerName)
        assertNull(order.buyerEmail)
        assertTrue(order.buyerPhone.length <= 32)
        assertEquals("JK-ABC123", order.reference)
        assertEquals(150_000, order.totalMinor)
    }
}
