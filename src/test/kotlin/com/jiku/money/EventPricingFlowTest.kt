package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.shared.TenantContext
import com.jiku.shared.UsageAllowanceGate
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Event pricing (JIKU-268): an event buys its guests at one tax-inclusive
 * price each, in the organization's billing currency, every delivery mode
 * included. Buying more charges only the guests not already paid for; the
 * first purchase leaves out the free guests still unused, and an event on its
 * day gets some room past its guests (JIKU-270).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EventPricingFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var billingModuleApi: BillingModuleApi

    @Autowired
    lateinit var allowanceGate: UsageAllowanceGate

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
    fun `an event pays only for the guests past the free ones`() {
        val token = api.register()
        val eventId = api.createEvent(token)

        api
            .get(token, "/api/v1/events/$eventId/billing/quote?guests=120")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amountMinor").value(20 * 750))
            .andExpect(jsonPath("$.paidGuests").value(0))
            .andExpect(jsonPath("$.freeGuests").value(100))
        api
            .get(token, "/api/v1/events/$eventId/billing/quote?guests=80")
            .andExpect(status().isConflict())

        api
            .get(token, "/api/v1/events/$eventId/usage")
            .andExpect(jsonPath("$.freeGuests").value(100))
        val first = requestGuests(token, eventId, 300)
        assertEquals(200 * 750, JsonPath.read<Int>(first, "$.amountMinor"))
        assertEquals(300, JsonPath.read<Int>(first, "$.guests"))
    }

    @Test
    fun `free guests left out of one purchase cannot serve another event`() {
        val token = api.register()
        val first = api.createEvent(token)
        val second = api.createEvent(token)

        billingModuleApi.adminConfirmManualPayment(UUID.fromString(JsonPath.read(requestGuests(token, first, 120), "$.paymentId")))

        api
            .get(token, "/api/v1/events/$second/billing/quote?guests=120")
            .andExpect(jsonPath("$.freeGuests").value(0))
            .andExpect(jsonPath("$.amountMinor").value(120 * 750))
    }

    @Test
    fun `an event on its day may send past its guests`() {
        val token = api.register()
        val today = eventStartingAt(token, Instant.now().plus(3, ChronoUnit.HOURS))
        val later = api.createEvent(token)

        TenantContext.set(api.tenantId(token))
        try {
            assertEquals(120, allowanceGate.allowanceCeiling(UUID.fromString(today), 0))
            assertEquals(100, allowanceGate.allowanceCeiling(UUID.fromString(later), 0))
        } finally {
            TenantContext.clear()
        }
    }

    @Test
    fun `buying more charges only the guests not yet paid for`() {
        val token = api.register()
        val eventId = api.createEvent(token)

        val first = requestGuests(token, eventId, 300)
        assertEquals(200 * 750, JsonPath.read<Int>(first, "$.amountMinor"))
        billingModuleApi.adminConfirmManualPayment(UUID.fromString(JsonPath.read(first, "$.paymentId")))
        api
            .get(token, "/api/v1/events/$eventId/usage")
            .andExpect(jsonPath("$.allowance").value(300))
            .andExpect(jsonPath("$.tier").value("PAID"))

        api
            .get(token, "/api/v1/events/$eventId/usage")
            .andExpect(jsonPath("$.paidGuests").value(300))
            .andExpect(jsonPath("$.freeGuests").value(0))
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

    private fun eventStartingAt(
        token: String,
        start: Instant,
    ): String =
        JsonPath.read(
            api
                .post(
                    token,
                    "/api/v1/events",
                    """{"name":"Mariage","timezone":"Africa/Conakry","startDateTime":"$start","invitationChannels":["EMAIL"]}""",
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

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
