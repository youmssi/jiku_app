package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.TicketTypeRepository
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import com.jiku.support.TestVerifications
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * JIKU-108: a ticket category or a service carries what the client pays the
 * organization, always in the organization's currency. A free one has no price.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PricingTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var verifications: TestVerifications

    @Autowired
    lateinit var ticketTypes: TicketTypeRepository

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a sold category is priced in the organization's currency, a free one has no price`() {
        val token = api.register()
        verifications.approve(api.tenantId(token))
        val eventId = api.createEvent(token)

        api
            .post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"VIP","priceMinor":50000}""")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.priceMinor").value(50000))
            .andExpect(jsonPath("$.currency").value("GNF"))
        api
            .post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"Invités"}""")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.priceMinor").doesNotExist())
            .andExpect(jsonPath("$.currency").doesNotExist())
    }

    @Test
    fun `a category's price is frozen once a ticket is confirmed`() {
        val token = api.register()
        verifications.approve(api.tenantId(token))
        val eventId = api.createEvent(token)
        val typeId =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"VIP","priceMinor":50000}""")
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        val tenantId =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/auth/me")
                    .andReturn()
                    .response.contentAsString,
                "$.tenantId",
            )
        TenantContext.withTenant(tenantId) {
            val type = ticketTypes.findById(UUID.fromString(typeId)).orElseThrow()
            type.confirmedCount = 1
            ticketTypes.save(type)
        }

        api
            .put(token, "/api/v1/events/$eventId/ticket-types/$typeId", """{"label":"VIP","priceMinor":60000}""")
            .andExpect(status().isConflict())
        api
            .put(token, "/api/v1/events/$eventId/ticket-types/$typeId", """{"label":"VIP Gold","priceMinor":50000}""")
            .andExpect(status().isOk())
    }

    @Test
    fun `a negative or zero price is refused`() {
        val token = api.register()
        verifications.approve(api.tenantId(token))
        val eventId = api.createEvent(token)

        api
            .post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"VIP","priceMinor":0}""")
            .andExpect(status().isBadRequest())
    }

    @Test
    fun `a service's payment rule and price change together`() {
        val token = api.register()
        verifications.approve(api.tenantId(token))
        val created =
            api
                .post(
                    token,
                    "/api/v1/services",
                    """{"name":"Consultation","timezone":"Africa/Conakry","paymentRule":"BEFORE","priceMinor":100000}""",
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentRule").value("BEFORE"))
                .andExpect(jsonPath("$.priceMinor").value(100000))
                .andExpect(jsonPath("$.currency").value("GNF"))
                .andReturn()
                .response.contentAsString
        val serviceId = JsonPath.read<String>(created, "$.id")

        // Switching between paid rules keeps the amount.
        api
            .patch(token, "/api/v1/services/$serviceId", """{"paymentRule":"AFTER_SERVICE"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceMinor").value(100000))

        api
            .patch(token, "/api/v1/services/$serviceId", """{"paymentRule":"FREE"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priceMinor").doesNotExist())
    }

    @Test
    fun `a service whose rule and price disagree is refused`() {
        val token = api.register()
        verifications.approve(api.tenantId(token))

        api
            .post(token, "/api/v1/services", """{"name":"Coupe","timezone":"Africa/Conakry","paymentRule":"BEFORE"}""")
            .andExpect(status().isBadRequest())
        api
            .post(token, "/api/v1/services", """{"name":"Coupe","timezone":"Africa/Conakry","paymentRule":"FREE","priceMinor":5000}""")
            .andExpect(status().isBadRequest())
    }
}
