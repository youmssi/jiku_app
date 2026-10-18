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
 * Event pricing (JIKU-268): an event buys its guests at one tax-inclusive
 * price each, in the organization's billing currency, every delivery mode
 * included. Buying more charges only the guests not already paid for.
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
    fun `the per-guest price is in the organization's currency`() {
        val token = api.register()

        api
            .get(token, "/api/v1/billing/event-pricing")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency").value("GNF"))
            .andExpect(jsonPath("$.perGuestMinor").value(750))
            .andExpect(jsonPath("$.freeTierGuests").value(100))
    }

    @Test
    fun `an event pays for each guest it buys`() {
        val token = api.register()
        val eventId = api.createEvent(token)

        api
            .get(token, "/api/v1/events/$eventId/billing/quote?guests=300")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amountMinor").value(300 * 750))
            .andExpect(jsonPath("$.paidGuests").value(0))

        val first = requestGuests(token, eventId, 300)
        assertEquals(300 * 750, JsonPath.read<Int>(first, "$.amountMinor"))
        assertEquals(300, JsonPath.read<Int>(first, "$.guests"))
    }

    @Test
    fun `buying more charges only the guests not yet paid for`() {
        val token = api.register()
        val eventId = api.createEvent(token)

        val first = requestGuests(token, eventId, 300)
        billingModuleApi.adminConfirmManualPayment(UUID.fromString(JsonPath.read(first, "$.paymentId")))
        api
            .get(token, "/api/v1/events/$eventId/usage")
            .andExpect(jsonPath("$.allowance").value(300))
            .andExpect(jsonPath("$.tier").value("PAID"))

        val more = requestGuests(token, eventId, 450)
        assertEquals((450 - 300) * 750, JsonPath.read<Int>(more, "$.amountMinor"))

        api
            .post(token, "/api/v1/events/$eventId/payments/manual", """{"guests":300}""")
            .andExpect(status().isConflict())
    }

    @Test
    fun `a guest count out of range is refused`() {
        val token = api.register()
        val eventId = api.createEvent(token)

        api
            .post(token, "/api/v1/events/$eventId/payments/manual", """{"guests":0}""")
            .andExpect(status().isBadRequest())
        api
            .get(token, "/api/v1/events/$eventId/billing/quote?guests=100001")
            .andExpect(status().isBadRequest())
    }

    private fun requestGuests(
        token: String,
        eventId: String,
        guests: Int,
    ): String =
        api
            .post(token, "/api/v1/events/$eventId/payments/manual", """{"guests":$guests}""")
            .andExpect(status().isOk())
            .andReturn()
            .response.contentAsString
}
