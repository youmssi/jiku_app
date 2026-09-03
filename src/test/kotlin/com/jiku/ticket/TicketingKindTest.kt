package com.jiku.ticket

import com.jiku.TestcontainersConfiguration
import com.jiku.shared.TenantContext
import com.jiku.ticket.internal.Ticket
import com.jiku.ticket.internal.TicketKind
import com.jiku.ticket.internal.TicketRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * JIKU-83 : le ticket porte désormais un type et un créneau, de façon purement
 * additive. Les tickets d'invitation restent INVITATION sans créneau ; un ticket
 * de rendez-vous persiste son créneau, son heure d'arrivée et son rang du jour ;
 * et uq_ticket_guest, qui protège capacité, transfert et double scan, n'est pas
 * affaibli.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TicketingKindTest {
    @Autowired
    lateinit var ticketing: TicketingModuleApi

    @Autowired
    lateinit var tickets: TicketRepository

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `a ticket issued through the flow is INVITATION with no slot`() {
        TenantContext.set("tenant-kind-invitation")
        val guestId = UUID.randomUUID()

        ticketing.issueTicket(UUID.randomUUID(), guestId)

        val saved = assertNotNull(tickets.findByGuestId(guestId))
        assertEquals(TicketKind.INVITATION, saved.kind)
        assertNull(saved.startsAt)
        assertNull(saved.endsAt)
        assertNull(saved.arrivedAt)
        assertNull(saved.dayRank)
    }

    @Test
    fun `an appointment ticket persists its slot, arrival and day rank`() {
        TenantContext.set("tenant-kind-appointment")
        val startsAt = Instant.parse("2026-11-02T09:00:00Z")
        val endsAt = Instant.parse("2026-11-02T09:30:00Z")
        val arrivedAt = Instant.parse("2026-11-02T08:52:00Z")

        val saved =
            tickets.save(
                Ticket(
                    eventId = UUID.randomUUID(),
                    guestId = UUID.randomUUID(),
                    ticketCode = "kind-appointment-code",
                ).apply {
                    kind = TicketKind.APPOINTMENT
                    this.startsAt = startsAt
                    this.endsAt = endsAt
                    this.arrivedAt = arrivedAt
                    dayRank = 3
                },
            )

        val reloaded = assertNotNull(tickets.findById(assertNotNull(saved.id)).get())
        assertEquals(TicketKind.APPOINTMENT, reloaded.kind)
        assertEquals(startsAt, reloaded.startsAt)
        assertEquals(endsAt, reloaded.endsAt)
        assertEquals(arrivedAt, reloaded.arrivedAt)
        assertEquals(3, reloaded.dayRank)
    }

    @Test
    fun `one ticket per guest is still enforced`() {
        TenantContext.set("tenant-kind-unique")
        val guestId = UUID.randomUUID()
        tickets.save(
            Ticket(eventId = UUID.randomUUID(), guestId = guestId, ticketCode = "kind-unique-code"),
        )

        // A second ticket for the same guest must be refused by uq_ticket_guest,
        // exactly as before JIKU-83.
        assertThrows<DataIntegrityViolationException> {
            tickets.save(
                Ticket(eventId = UUID.randomUUID(), guestId = guestId, ticketCode = "kind-unique-code-2"),
            )
        }
    }
}
