package com.jiku.ticket

import com.jiku.TestcontainersConfiguration
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TicketingTest {
    @Autowired
    lateinit var ticketing: TicketingModuleApi

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `issues a unique ticket, is idempotent, and can be cancelled`() {
        TenantContext.set("tenant-tickets")
        val eventId = UUID.randomUUID()
        val guestId = UUID.randomUUID()

        val ticket = ticketing.issueTicket(eventId, guestId)
        assertTrue(ticket.ticketCode.isNotBlank())
        assertEquals("ISSUED", ticket.status)

        // Issuing again for the same guest returns the same ticket.
        assertEquals(ticket.ticketCode, ticketing.issueTicket(eventId, guestId).ticketCode)

        // Resolvable by code.
        assertNotNull(ticketing.findByCode(ticket.ticketCode))

        // Cancellation flips the status.
        ticketing.cancelByGuest(guestId)
        assertEquals("CANCELLED", ticketing.findByCode(ticket.ticketCode)?.status)
    }
}
