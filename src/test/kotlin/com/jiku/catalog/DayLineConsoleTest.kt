package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.DayLineConsoleService
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceConfigService
import com.jiku.catalog.internal.ServiceConfigUpdate
import com.jiku.invitation.internal.GuestRepository
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * La console de ligne du jour côté catalog (JIKU-88) : l'inscription d'un
 * sans-rendez-vous au comptoir matérialise l'invité et son billet (via l'écouteur
 * du module invitation), la ligne renvoyée les contient, et le refus d'un service
 * d'accueillir des sans-rendez-vous est respecté.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DayLineConsoleTest {
    @Autowired
    lateinit var console: DayLineConsoleService

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var config: ServiceConfigService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var guests: GuestRepository

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `a walk-in at the counter appears on today's line and can be served`() {
        TenantContext.set("console-walkin")
        val serviceId = serviceWithMorning()

        val line = console.walkIn(serviceId, "Aïssatou Barry", "+224600000020")

        val entry = line.entries.single()
        assertEquals("WALK_IN", entry.kind)
        assertEquals("WAITING", entry.status)
        assertEquals("Aïssatou Barry", entry.clientName)
        assertEquals(1, entry.dayRank)

        // L'invité sans événement a bien été matérialisé par le module invitation.
        assertTrue(guests.findAllByEventIdIsNull().any { it.firstName == "Aïssatou Barry" })

        // « Suivant » appelle le seul sans-rendez-vous en attente.
        val called = assertNotNull(console.next(serviceId))
        assertEquals(entry.ticketCode, called.ticketCode)
        assertEquals("CALLED", called.status)
    }

    @Test
    fun `a service that refuses walk-ins rejects the counter registration`() {
        TenantContext.set("console-walkin-refused")
        val serviceId = serviceWithMorning()
        config.update(serviceId, ServiceConfigUpdate(walkInsAllowed = false))

        try {
            console.walkIn(serviceId, "Binta", "+224600000021")
            fail("Une inscription sans-rendez-vous devait être refusée")
        } catch (ex: ResponseStatusException) {
            assertEquals(HttpStatus.CONFLICT, ex.statusCode)
        }
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
}
