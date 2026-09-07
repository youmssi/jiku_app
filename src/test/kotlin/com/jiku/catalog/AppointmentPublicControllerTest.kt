package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.ConfirmationMode
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceConfigService
import com.jiku.catalog.internal.ServiceConfigUpdate
import com.jiku.catalog.internal.ServiceLinkTokenService
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalTime
import java.util.UUID

/**
 * Parcours client (JIKU-87) : le lien de service signé, résolu sans compte,
 * expose le service et ses créneaux ; le client réserve, consulte et annule sa
 * réservation par son jeton, sans jamais créer de compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AppointmentPublicControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var linkTokens: ServiceLinkTokenService

    @Autowired
    lateinit var configService: ServiceConfigService

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `an anonymous client resolves the shared service link and its open slots`() {
        val (token, _) = seedService("svc-link-tenant")

        mockMvc
            .perform(get("/api/v1/appointments/$token").param("date", "2026-11-02"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Coupe"))
            .andExpect(jsonPath("$.confirmationMode").value("ON_REQUEST"))
            .andExpect(jsonPath("$.professionals[0]").value("Coiffeuse"))
            .andExpect(jsonPath("$.slots.length()").value(8))

        mockMvc
            .perform(get("/api/v1/appointments/not-a-real-token"))
            .andExpect(status().isNotFound())
    }

    @Test
    fun `a client books on request, cannot double book, and cancels within the deadline`() {
        val (token, _) = seedService("svc-book-tenant")
        val bookBody =
            """{"clientName":"Fatou","clientPhone":"+224600000000","startsAt":"2026-11-02T09:00:00Z"}"""

        // Sur demande : le créneau est bloqué en PENDING, un jeton est remis.
        val created =
            mockMvc
                .perform(
                    post("/api/v1/appointments/$token/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookBody),
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .response.contentAsString
        val bookingToken = JsonPath.read<String>(created, "$.bookingToken")

        // Le même créneau n'est plus réservable pour un second client.
        mockMvc
            .perform(
                post("/api/v1/appointments/$token/book")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bookBody),
            ).andExpect(status().isConflict())

        // Le client consulte sa réservation par son jeton.
        mockMvc
            .perform(get("/api/v1/appointments/$token/booking/$bookingToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.clientName").value("Fatou"))

        // Annulation dans le délai : le créneau est libéré, le jeton ne répond plus.
        mockMvc
            .perform(delete("/api/v1/appointments/$token/booking/$bookingToken"))
            .andExpect(status().isNoContent())
        mockMvc
            .perform(get("/api/v1/appointments/$token/booking/$bookingToken"))
            .andExpect(status().isNotFound())
    }

    @Test
    fun `an instantaneous service confirms the booking immediately`() {
        val (token, serviceId) = seedService("svc-book-instant")
        TenantContext.set("svc-book-instant")
        configService.update(serviceId, ServiceConfigUpdate(confirmationMode = ConfirmationMode.INSTANTANEOUS))
        TenantContext.clear()

        mockMvc
            .perform(
                post("/api/v1/appointments/$token/book")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"clientName":"Mariam","clientPhone":"+224600000001","startsAt":"2026-11-02T10:00:00Z"}""",
                    ),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    private fun seedService(tenant: String): Pair<String, UUID> {
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
        configService.update(service.id, ServiceConfigUpdate(maxHorizonDays = 365))
        val token = linkTokens.issue(service.id, tenant)
        TenantContext.clear()
        return token to service.id
    }
}
