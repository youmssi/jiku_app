package com.jiku.tenant

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceCreateRequest
import com.jiku.catalog.internal.ServiceLinkCodeService
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import com.jiku.support.TestVerifications
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Profil public d'organisation : l'identifiant public ouvre la page découverte
 * (nom, logo) et liste les services réservables par lien court. Un identifiant
 * inconnu ou une organisation suspendue restent introuvables.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PublicOrgProfileTest {
    @Autowired
    lateinit var tenants: TenantModuleApi

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var codes: ServiceLinkCodeService

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var verifications: TestVerifications

    private val api by lazy { OrganizerApi(mockMvc) }

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `the public profile lists bookable services with their short links`() {
        val tenantId = UUID.fromString(api.tenantId(api.register()))
        val username = "salon-aicha"
        tenants.updateUsername(tenantId, username)

        TenantContext.set(tenantId.toString())
        resources.save(Resource(name = "Coiffeuse", type = com.jiku.catalog.ResourceType.PERSON, timezone = "Africa/Conakry"))
        val service = services.create(ServiceCreateRequest(name = "Coloration", timezone = "Africa/Conakry"))
        val code = codes.forService(service.id, tenantId.toString()).code
        TenantContext.clear()

        val profile = tenants.findByUsername(username.uppercase())
        assertNotNull(profile, "username lookup must be case-insensitive")
        assertEquals(tenantId, profile.id)

        mockMvc
            .perform(get("/api/v1/public/orgs/$username"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizationName").value("Test Org"))
            .andExpect(jsonPath("$.services[0].name").value("Coloration"))
            .andExpect(jsonPath("$.services[0].shortCode").value(code))
            .andExpect(jsonPath("$.verification").doesNotExist())

        // Once the Jikū team approves it, the profile carries the trust badge (référentiel §9).
        verifications.approve(tenantId.toString())
        mockMvc
            .perform(get("/api/v1/public/orgs/$username"))
            .andExpect(jsonPath("$.verification").value("COMPANY"))

        mockMvc
            .perform(get("/api/v1/public/orgs/no-such-org"))
            .andExpect(status().isNotFound())
    }
}
