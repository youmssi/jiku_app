package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.DayLineTokenService
import com.jiku.catalog.internal.LineCodeController
import com.jiku.catalog.internal.LineStaffController
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceCreateRequest
import com.jiku.catalog.internal.ServiceLinkTokenService
import com.jiku.catalog.internal.ServiceStaff
import com.jiku.catalog.internal.ServiceStaffRepository
import com.jiku.invitation.internal.Guest
import com.jiku.invitation.internal.GuestRepository
import com.jiku.shared.TenantContext
import com.jiku.ticket.TicketingModuleApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Le lien du personnel vers la console d'un service (JIKU-88), même patron que le
 * lien validateurs : jeton signé + ligne révocable tenant-scopée. Un lien du
 * personnel ne sert que son service ; un code d'un autre service ou d'un autre
 * tenant est refusé ; un lien révoqué ne résout plus.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class LineStaffControllerTest {
    @Autowired
    lateinit var lineController: LineStaffController

    @Autowired
    lateinit var lineCodeController: LineCodeController

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var staff: ServiceStaffRepository

    @Autowired
    lateinit var guests: GuestRepository

    @Autowired
    lateinit var tokens: DayLineTokenService

    @Autowired
    lateinit var bookingTokens: ServiceLinkTokenService

    @Autowired
    lateinit var ticketing: TicketingModuleApi

    private val zone = ZoneId.of("Africa/Conakry")

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `a staff link serves its service console and can be revoked`() {
        TenantContext.set("line-staff-a")
        val serviceId = serviceWithMorning()
        val code = createWalkIn(serviceId, "Staff-test", "+224600000030")
        val link = issueStaffLink(serviceId, "Comptoir A")

        // La console du service est servie par le lien : la personne en attente y est.
        val view = lineController.view(link)
        assertEquals("Coupe", view.serviceName)
        assertEquals(1, view.entries.size)

        // « Suivant » l'appelle.
        val next = lineController.next(link)
        assertEquals(code, next.ticket?.ticketCode)
        assertEquals("CALLED", next.ticket?.status)

        // Révocation : le lien ne sert plus rien.
        revokeStaffLink(link)
        expectNotFound { lineController.view(link) }
    }

    @Test
    fun `a staff link never serves a ticket of another service or tenant`() {
        TenantContext.set("line-staff-a")
        val serviceId = serviceWithMorning()
        val otherService = serviceWithMorning()
        val otherCode = createWalkIn(otherService, "Autre-service", "+224600000031")
        val link = issueStaffLink(serviceId, "Comptoir A")

        // Code d'un autre service du même tenant : refusé (conflit d'état), jamais servi.
        expectConflict { lineController.present(link, otherCode) }

        // Un code d'un autre tenant : introuvable pour le tenant du lien.
        TenantContext.set("line-staff-b")
        val foreignService = serviceWithMorning()
        val foreignCode = createWalkIn(foreignService, "Autre-tenant", "+224600000032")

        TenantContext.set("line-staff-a")
        expectNotFound { lineController.present(link, foreignCode) }
    }

    @Test
    fun `a booking link can never be used as a day-line link`() {
        TenantContext.set("line-staff-a")
        val serviceId = serviceWithMorning()
        val serviceLink = bookingTokens.issue(serviceId, "line-staff-a")

        expectNotFound { lineController.view(serviceLink) }
    }

    @Test
    fun `a staff link's short code resolves to a working token and stops resolving once revoked`() {
        TenantContext.set("line-staff-a")
        val serviceId = serviceWithMorning()
        val code = "TEST${UUID.randomUUID().toString().take(6).uppercase()}"
        val row = staff.save(ServiceStaff(serviceId = serviceId, label = "Comptoir A").apply { this.code = code })
        TenantContext.clear()

        // The code is a public entry point: no tenant needs to be bound to resolve it.
        val resolution = lineCodeController.resolve(code)
        val view = lineController.view(resolution.token)
        assertEquals("Coupe", view.serviceName)

        // An unknown code never resolves.
        expectNotFound { lineCodeController.resolve("UNKNOWNCODE") }

        // Once revoked, the code stops resolving even though the row still exists.
        TenantContext.set("line-staff-a")
        val toRevoke = staff.findById(requireNotNull(row.id)).orElseThrow()
        toRevoke.revoke()
        staff.save(toRevoke)
        TenantContext.clear()
        expectNotFound { lineCodeController.resolve(code) }
    }

    private fun issueStaffLink(
        serviceId: UUID,
        label: String,
    ): String {
        val row = staff.save(ServiceStaff(serviceId = serviceId, label = label))
        return tokens.issue(requireNotNull(row.id), serviceId, "line-staff-a")
    }

    private fun revokeStaffLink(token: String) {
        val id = UUID.fromString(tokens.parse(token).subject)
        TenantContext.set("line-staff-a")
        try {
            val row = staff.findById(id).orElseThrow()
            row.revoke()
            staff.save(row)
        } finally {
            TenantContext.clear()
        }
    }

    private fun createWalkIn(
        serviceId: UUID,
        name: String,
        phone: String,
    ): String {
        val guest = guests.save(Guest(eventId = null, firstName = name, lastName = "", phoneNumber = phone))
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        return ticketing
            .issueWalkIn(
                guestId = requireNotNull(guest.id),
                serviceId = serviceId,
                clientName = name,
                clientPhone = phone,
                professionalName = null,
                arrivedAt = Instant.now(),
                dayStart = start,
                dayEnd = end,
                rankDay = today,
            ).ticketCode
    }

    private fun expectNotFound(block: () -> Any?) {
        try {
            block()
            fail("Une réponse introuvable était attendue")
        } catch (ex: ResponseStatusException) {
            assertEquals(HttpStatus.NOT_FOUND, ex.statusCode)
        }
    }

    private fun expectConflict(block: () -> Any?) {
        try {
            block()
            fail("Un conflit d'état était attendu")
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
        val service = services.create(ServiceCreateRequest(name = "Coupe", timezone = "Africa/Conakry"))
        services.addRequirement(service.id, ResourceType.PERSON, 1)
        return service.id
    }
}
