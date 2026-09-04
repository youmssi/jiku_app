package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceLinkTokenService
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalTime

/**
 * Parcours client (JIKU-87) : le lien de service signé, résolu sans compte,
 * expose le service, ses professionnels et ses créneaux dans le tenant de
 * l'organisateur.
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

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `an anonymous client resolves the shared service link and its open slots`() {
        val tenant = "svc-link-tenant"
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
        val token = linkTokens.issue(service.id, tenant)

        // Le client n'a ni compte ni session : seul le lien compte.
        mockMvc
            .perform(get("/api/v1/appointments/$token").param("date", "2026-11-02"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Coupe"))
            .andExpect(jsonPath("$.confirmationMode").value("ON_REQUEST"))
            .andExpect(jsonPath("$.professionals[0]").value("Coiffeuse"))
            .andExpect(jsonPath("$.slots.length()").value(8))

        // Un lien invalide ne révèle rien.
        mockMvc
            .perform(get("/api/v1/appointments/not-a-real-token"))
            .andExpect(status().isNotFound())
    }
}
