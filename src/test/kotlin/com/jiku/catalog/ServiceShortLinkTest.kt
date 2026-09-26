package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceConfigService
import com.jiku.catalog.internal.ServiceConfigUpdate
import com.jiku.catalog.internal.ServiceCreateRequest
import com.jiku.catalog.internal.ServiceLinkCodeService
import com.jiku.shared.TenantContext
import com.jiku.support.TestDates.MONDAY
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Lien court de réservation (profil public) : le code remplace le jeton signé
 * dans l'URL partagée, se résout sans compte comme le jeton, et reste stable
 * d'un appel à l'autre (jamais de second code pour un même service).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ServiceShortLinkTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var codes: ServiceLinkCodeService

    @Autowired
    lateinit var configService: ServiceConfigService

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `a service keeps one stable short code and the code resolves the same slots`() {
        val (serviceId, tenant) = seedService("short-link-tenant")
        val first = codes.forService(serviceId, tenant)
        val second = codes.forService(serviceId, tenant)
        assertEquals(first.code, second.code, "one service must never receive a second code")
        assertEquals(tenant, first.tenantId)

        mockMvc
            .perform(get("/api/v1/r/${first.code}").param("date", "$MONDAY"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Coupe"))
            .andExpect(jsonPath("$.slots.length()").value(8))

        mockMvc
            .perform(get("/api/v1/r/NOT-A-REAL-CODE"))
            .andExpect(status().isNotFound())
    }

    @Test
    fun `a client books through the short link without an account`() {
        val (serviceId, tenant) = seedService("short-book-tenant")
        val code = codes.forService(serviceId, tenant).code

        val body =
            mockMvc
                .perform(
                    post("/api/v1/r/$code/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"clientName":"Fatou","clientPhone":"+224600000000","startsAt":"${MONDAY}T10:00:00Z"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        val bookingToken = JsonPath.read<String>(body, "$.bookingToken")
        assertNotEquals("", bookingToken)

        mockMvc
            .perform(get("/api/v1/r/$code/booking/$bookingToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clientName").value("Fatou"))
    }

    private fun seedService(tenant: String): Pair<UUID, String> {
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
        val service = services.create(ServiceCreateRequest(name = "Coupe", timezone = "Africa/Conakry"))
        services.addRequirement(service.id, ResourceType.PERSON, 1)
        configService.update(service.id, ServiceConfigUpdate(maxHorizonDays = 365))
        TenantContext.clear()
        return service.id to tenant
    }
}
