package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.SlotEngine
import com.jiku.invitation.internal.Guest
import com.jiku.invitation.internal.GuestRepository
import com.jiku.shared.TenantContext
import com.jiku.ticket.LineOutcome
import com.jiku.ticket.TicketingModuleApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * La ligne du jour (JIKU-88) : le billet de rendez-vous devient la ligne —
 * arrivée (rang), appel, prise en charge, fin ou absent — pilotée par la règle
 * « suivant ». Les transitions sont atomiques (double arrivée refusée) et
 * bornées au service. Les sans-rendez-vous s'intercalent à l'heure de leur
 * arrivée entre les rendez-vous.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ServiceDayLineTest {
    @Autowired
    lateinit var engine: SlotEngine

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var guests: GuestRepository

    @Autowired
    lateinit var ticketing: TicketingModuleApi

    @AfterEach
    fun clearContext() = TenantContext.clear()

    private val dayStart = Instant.parse("2026-11-02T00:00:00Z")
    private val dayEnd = Instant.parse("2026-11-03T00:00:00Z")

    @Test
    fun `an appointment travels the whole day line from arrival to done`() {
        val tenant = "dayline-full"
        TenantContext.set(tenant)
        val serviceId = serviceWithMorning()

        engine.bookClient(serviceId, Instant.parse("2026-11-02T09:00:00Z"), "Fatou", "+224600000000")
        val code = dayLine(serviceId).single().ticketCode

        // Personne en attente, rien à appeler.
        assertNull(ticketing.callNext(serviceId, dayStart, dayEnd, Instant.parse("2026-11-02T09:00:00Z"), 10))

        val arrived = ticketing.arriveByCode(serviceId, code, Instant.parse("2026-11-02T08:55:00Z"), dayStart, dayEnd)
        assertEquals(LineOutcome.OK, arrived.outcome)
        assertEquals(1, assertNotNull(arrived.ticket).dayRank)
        assertEquals("WAITING", arrived.ticket.status)

        val called = ticketing.callByCode(serviceId, code)
        assertEquals(LineOutcome.OK, called.outcome)
        assertEquals("CALLED", assertNotNull(called.ticket).status)

        val present = ticketing.presentByCode(serviceId, code)
        assertEquals(LineOutcome.OK, present.outcome)
        assertEquals("IN_SERVICE", assertNotNull(present.ticket).status)

        val done = ticketing.finishByCode(serviceId, code)
        assertEquals(LineOutcome.OK, done.outcome)
        assertEquals("DONE", assertNotNull(done.ticket).status)
        assertEquals("Fatou", done.ticket.clientName)
    }

    @Test
    fun `a called client who never shows is marked absent and can be recalled`() {
        val tenant = "dayline-noshow"
        TenantContext.set(tenant)
        val serviceId = serviceWithMorning()

        engine.bookClient(serviceId, Instant.parse("2026-11-02T09:00:00Z"), "Aminata", "+224600000001")
        val code = dayLine(serviceId).single().ticketCode

        ticketing.arriveByCode(serviceId, code, Instant.parse("2026-11-02T08:55:00Z"), dayStart, dayEnd)
        ticketing.callByCode(serviceId, code)

        val absent = ticketing.noShowByCode(serviceId, code)
        assertEquals(LineOutcome.OK, absent.outcome)
        assertEquals("NO_SHOW", assertNotNull(absent.ticket).status)

        // Rappelé : ABSENT → EN_COURS reste possible.
        val recalled = ticketing.presentByCode(serviceId, code)
        assertEquals(LineOutcome.OK, recalled.outcome)
        assertEquals("IN_SERVICE", assertNotNull(recalled.ticket).status)
    }

    @Test
    fun `arrivals get a sequential day rank and a double arrival is refused`() {
        val tenant = "dayline-ranks"
        TenantContext.set(tenant)
        val serviceId = serviceWithMorning()
        val slot = Instant.parse("2026-11-02T09:00:00Z")

        engine.bookClient(serviceId, slot, "Mariam", "+224600000002")
        engine.bookClient(serviceId, slot.plusSeconds(1800), "Binta", "+224600000003")
        engine.bookClient(serviceId, slot.plusSeconds(3600), "Kadiatou", "+224600000004")

        val codes = dayLine(serviceId).map { it.ticketCode }
        assertEquals(3, codes.size)

        assertEquals(LineOutcome.OK, ticketing.arriveByCode(serviceId, codes[0], slot.minusSeconds(300), dayStart, dayEnd).outcome)
        assertEquals(LineOutcome.OK, ticketing.arriveByCode(serviceId, codes[1], slot.plusSeconds(1500), dayStart, dayEnd).outcome)
        assertEquals(LineOutcome.OK, ticketing.arriveByCode(serviceId, codes[2], slot.plusSeconds(3000), dayStart, dayEnd).outcome)

        val ranks = dayLine(serviceId).map { it.dayRank }
        assertEquals(listOf(1, 2, 3), ranks)

        // Le double scan est refusé : la transition est gardée par l'état.
        val again = ticketing.arriveByCode(serviceId, codes[0], slot.plusSeconds(3600), dayStart, dayEnd)
        assertEquals(LineOutcome.WRONG_STATE, again.outcome)
        assertEquals("WAITING", assertNotNull(again.ticket).status)
    }

    @Test
    fun `next applies the rule - a running arrived slot first, then the longest wait`() {
        val tenant = "dayline-next"
        TenantContext.set(tenant)
        val serviceId = serviceWithMorning()

        val slot = Instant.parse("2026-11-02T09:00:00Z")
        engine.bookClient(serviceId, slot, "A", "+224600000005")
        engine.bookClient(serviceId, slot.plusSeconds(1800), "B", "+224600000006")
        val codes = dayLine(serviceId).map { it.ticketCode }

        // Personne n'est encore arrivé : rien à appeler.
        assertNull(ticketing.callNext(serviceId, dayStart, dayEnd, slot.plusSeconds(1920), 10))

        // « A » (créneau 09:00) arrive à 08:55 ; « B » (créneau 09:30) à 09:32.
        ticketing.arriveByCode(serviceId, codes[0], slot.minusSeconds(300), dayStart, dayEnd)
        ticketing.arriveByCode(serviceId, codes[1], slot.plusSeconds(1920), dayStart, dayEnd)

        // À 09:32 le créneau de B est en cours et B est arrivé : B prime sur A.
        val first = assertNotNull(ticketing.callNext(serviceId, dayStart, dayEnd, slot.plusSeconds(1920), 10))
        assertEquals(codes[1], first.ticketCode)
        assertEquals("CALLED", first.status)

        // B est passé APPELÉ : plus aucun créneau en cours, la plus longue attente (A) l'emporte.
        val second = assertNotNull(ticketing.callNext(serviceId, dayStart, dayEnd, slot.plusSeconds(3000), 10))
        assertEquals(codes[0], second.ticketCode)

        // Tout le monde a été appelé : personne à appeler.
        assertNull(ticketing.callNext(serviceId, dayStart, dayEnd, slot.plusSeconds(3600), 10))
    }

    @Test
    fun `a walk-in interleaves by its arrival time between the appointments`() {
        val tenant = "dayline-order"
        TenantContext.set(tenant)
        val serviceId = serviceWithMorning()
        val slot = Instant.parse("2026-11-02T09:00:00Z")

        engine.bookClient(serviceId, slot, "Alpha", "+224600000007")
        engine.bookClient(serviceId, slot.plusSeconds(1800), "Charlie", "+224600000008")

        // Sans-rendez-vous arrivé à 09:10, entre le créneau de 09:00 et celui de 09:30.
        val guest = guests.save(Guest(eventId = null, firstName = "Binta", lastName = "", phoneNumber = "+224600000009"))
        ticketing.issueWalkIn(
            guestId = requireNotNull(guest.id),
            serviceId = serviceId,
            clientName = "Binta",
            clientPhone = "+224600000009",
            professionalName = null,
            arrivedAt = Instant.parse("2026-11-02T09:10:00Z"),
            dayStart = dayStart,
            dayEnd = dayEnd,
        )

        val line = dayLine(serviceId)
        assertEquals(3, line.size)
        // L'ordre d'affichage suit l'heure : 09:00 (RDV), 09:10 (sans RDV), 09:30 (RDV).
        assertEquals("APPOINTMENT", line[0].kind)
        assertEquals("WALK_IN", line[1].kind)
        assertEquals("APPOINTMENT", line[2].kind)
        assertEquals("Binta", line[1].clientName)
    }

    private fun serviceWithMorning(): UUID {
        val resource = resources.save(Resource(name = "Coiffeuse", type = ResourceType.PERSON, timezone = "Africa/Conakry"))
        availabilities.save(
            ResourceAvailability(
                resourceId = requireNotNull(resource.id),
                dayOfWeek = 1,
                start = LocalTime.of(9, 0),
                end = LocalTime.of(13, 0),
            ),
        )
        val service = services.create("Coupe", "Africa/Conakry")
        services.addRequirement(service.id, ResourceType.PERSON, 1)
        return service.id
    }

    private fun dayLine(serviceId: UUID) = ticketing.serviceLine(serviceId, dayStart, dayEnd)
}
