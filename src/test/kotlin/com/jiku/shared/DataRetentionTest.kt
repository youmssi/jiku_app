package com.jiku.shared

import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.ProspectRetentionJob
import com.jiku.catalog.ResourceType
import com.jiku.catalog.internal.ConfirmationMode
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceClientRetentionJob
import com.jiku.catalog.internal.ServiceConfigService
import com.jiku.catalog.internal.ServiceConfigUpdate
import com.jiku.catalog.internal.ServiceCreateRequest
import com.jiku.catalog.internal.SlotEngine
import com.jiku.support.TestDates.MONDAY
import com.jiku.ticket.internal.LineTicketRetentionJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.test.assertEquals

/**
 * JIKU-267: past the retention window, a service client's name and number are
 * removed from the reservation and from the line ticket, and prospects are
 * deleted; what is still within the window is left alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DataRetentionTest {
    @Autowired
    lateinit var engine: SlotEngine

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var configService: ServiceConfigService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var reservationRetention: ServiceClientRetentionJob

    @Autowired
    lateinit var lineTicketRetention: LineTicketRetentionJob

    @Autowired
    lateinit var prospectRetention: ProspectRetentionJob

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mockMvc: MockMvc

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `a service client's details are removed once the appointment is past retention`() {
        TenantContext.set("retention-service")
        val serviceId = serviceWithMorning()
        val startsAt = Instant.parse("${MONDAY}T09:00:00Z")
        engine.bookClient(serviceId, startsAt, "Fatou", PHONE)
        TenantContext.clear()

        assertEquals(0, reservationRetention.anonymizeBefore(startsAt))
        assertEquals(1, clientsWith("service_reservation"))

        reservationRetention.anonymizeBefore(startsAt.plus(Duration.ofDays(1)))
        lineTicketRetention.anonymizeBefore(startsAt.plus(Duration.ofDays(1)))

        assertEquals(0, clientsWith("service_reservation"))
        assertEquals(0, clientsWith("ticket"))
    }

    @Test
    fun `prospects are deleted once past retention`() {
        val phone = "+2246${(1000000..9999999).random()}"
        mockMvc
            .perform(
                post("/api/v1/prospects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"businessName":"Salle Horizon","contactName":"Aïcha Diallo","phone":"$phone","sector":"AUTRE","city":"Conakry"}""",
                    ),
            ).andExpect(status().is2xxSuccessful)

        prospectRetention.purgeBefore(Instant.now().minus(Duration.ofDays(1)))
        assertEquals(1, prospects(phone))

        prospectRetention.purgeBefore(Instant.now().plus(Duration.ofMinutes(1)))
        assertEquals(0, prospects(phone))
    }

    private fun clientsWith(table: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE client_phone = ?", Int::class.java, PHONE) ?: 0

    private fun prospects(phone: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM prospect_lead WHERE phone = ?", Int::class.java, phone) ?: 0

    private fun serviceWithMorning(): java.util.UUID {
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
        configService.update(
            service.id,
            ServiceConfigUpdate(confirmationMode = ConfirmationMode.INSTANTANEOUS, maxHorizonDays = 365),
        )
        return service.id
    }

    private companion object {
        const val PHONE = "+224600267267"
    }
}
