package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.SlotEngine
import com.jiku.invitation.internal.GuestRepository
import com.jiku.shared.TenantContext
import com.jiku.ticket.internal.TicketKind
import com.jiku.ticket.internal.TicketRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Réservation de rendez-vous sans compte (JIKU-87) : le billet de rendez-vous est
 * émis automatiquement — l'invité n'appartient à aucun événement et le billet
 * porte le créneau.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AppointmentIssuanceTest {
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
    lateinit var tickets: TicketRepository

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `a client booking issues an event-less appointment ticket`() {
        val tenant = "appt-issue-tenant"
        TenantContext.set(tenant)
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
        val slot = Instant.parse("2026-11-02T09:00:00Z")

        val outcome = engine.bookClient(service.id, slot, "Fatou", "+224600000000")
        assertEquals("PENDING", outcome.status.name)

        // L'invité existe, sans événement.
        val guest = guests.findAllByEventIdIsNull().single()
        assertEquals("Fatou", guest.firstName)
        assertEquals("+224600000000", guest.phoneNumber)
        assertNull(guest.eventId)

        // Son billet de rendez-vous porte le créneau.
        val ticket = tickets.findByGuestId(requireNotNull(guest.id))
        val appointment = assertNotNull(ticket)
        assertNull(appointment.eventId)
        assertEquals(slot, appointment.startsAt)
        assertEquals(slot.plusSeconds(1800), appointment.endsAt)
        assertEquals(TicketKind.APPOINTMENT, appointment.kind)
    }
}
