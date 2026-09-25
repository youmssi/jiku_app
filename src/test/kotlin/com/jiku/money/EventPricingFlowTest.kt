package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Event pricing (ADR 105): tiers are priced in the organization's billing
 * currency, moving up a tier charges only the difference with the tier already
 * paid, and an event beyond the last tier adds a local per-guest price.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EventPricingFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var billingModuleApi: BillingModuleApi

    private lateinit var api: OrganizerApi

    @BeforeEach
    fun setUp() {
        api = OrganizerApi(mockMvc)
    }

    @Test
    fun `the catalog is priced in the organization's currency`() {
        val token = api.register()

        api
            .get(token, "/api/v1/billing/tiers")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency").value("GNF"))
            .andExpect(jsonPath("$.tiers[?(@.name == 'BRONZE')].priceMinor").value(225_000))
            .andExpect(jsonPath("$.custom.beyondPerGuestMinor").value(500))
        api
            .get(token, "/api/v1/billing/tiers/custom-quote?guestCount=1500")
            .andExpect(jsonPath("$.priceMinor").value(600_000 + 500 * 500))
            .andExpect(jsonPath("$.currency").value("GNF"))
    }

    @Test
    fun `moving up a tier charges only the difference`() {
        val token = api.register()
        val eventId = api.createEvent(token)

        val bronze = requestTier(token, eventId, "BRONZE")
        assertEquals(225_000, JsonPath.read<Int>(bronze, "$.amountMinor"))
        billingModuleApi.adminConfirmManualPayment(UUID.fromString(JsonPath.read(bronze, "$.paymentId")))

        val argent = requestTier(token, eventId, "ARGENT")
        assertEquals(375_000 - 225_000, JsonPath.read<Int>(argent, "$.amountMinor"))

        api
            .post(token, "/api/v1/events/$eventId/payments/manual", """{"tier":"BRONZE"}""")
            .andExpect(status().isConflict())
    }

    private fun requestTier(
        token: String,
        eventId: String,
        tier: String,
    ): String =
        api
            .post(token, "/api/v1/events/$eventId/payments/manual", """{"tier":"$tier"}""")
            .andExpect(status().isOk())
            .andReturn()
            .response.contentAsString
}
