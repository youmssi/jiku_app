package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.money.internal.OrganizerPackService
import com.jiku.shared.TenantContext
import com.jiku.shared.UsageAllowanceGate
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.AfterEach
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
 * The Organizer Pack (ADR 105): a month of 1 000 guests shared by every event,
 * extra guests bought ahead in blocks, an event day that never stops sending,
 * and the guests it sent past the allowance paid with the next renewal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OrganizerPackFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var billingModuleApi: BillingModuleApi

    @Autowired
    lateinit var organizerPack: OrganizerPackService

    @Autowired
    lateinit var allowanceGate: UsageAllowanceGate

    private lateinit var api: OrganizerApi

    @BeforeEach
    fun setUp() {
        api = OrganizerApi(mockMvc)
    }

    @AfterEach
    fun clearTenant() {
        TenantContext.clear()
    }

    @Test
    fun `a pack covers every event for the month, extra guests are bought ahead and owed ones renew with it`() {
        val token = api.register()
        api
            .get(token, "/api/v1/billing/pack")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false))
            .andExpect(jsonPath("$.monthlyMinor").value(600_000))
            .andExpect(jsonPath("$.extraPerGuestMinor").value(600))
        api.post(token, "/api/v1/billing/pack/extra", """{"blocks":1}""").andExpect(status().isConflict())

        confirm(request(token, "/api/v1/billing/pack/request", """{"months":1}""", 600_000))
        api
            .get(token, "/api/v1/billing/pack")
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.remainingGuests").value(1_000))

        val eventId = api.createEvent(token)
        api
            .get(token, "/api/v1/events/$eventId/usage")
            .andExpect(jsonPath("$.tier").value("PACK"))
            .andExpect(jsonPath("$.allowance").value(1_000))
        api.get(token, "/api/v1/events/$eventId/billing/quotes").andExpect(jsonPath("$.length()").value(0))
        api.post(token, "/api/v1/events/$eventId/payments/manual", """{"tier":"BRONZE"}""").andExpect(status().isConflict())

        confirm(request(token, "/api/v1/billing/pack/extra", """{"blocks":2}""", 200 * 600))
        api.get(token, "/api/v1/billing/pack").andExpect(jsonPath("$.remainingGuests").value(1_200))

        TenantContext.set(api.tenantId(token))
        organizerPack.recordCommitment(1_150)
        organizerPack.recordCommitment(100)
        TenantContext.clear()
        api
            .get(token, "/api/v1/billing/pack")
            .andExpect(jsonPath("$.usedGuests").value(1_250))
            .andExpect(jsonPath("$.remainingGuests").value(0))
            .andExpect(jsonPath("$.owedGuests").value(50))

        confirm(request(token, "/api/v1/billing/pack/request", """{"months":1}""", 600_000 + 50 * 600))
        api.get(token, "/api/v1/billing/pack").andExpect(jsonPath("$.owedGuests").value(0))
        request(token, "/api/v1/billing/pack/request", """{"months":12}""", 600_000 * 10)
    }

    @Test
    fun `an event never stops sending on its day, even with the month used up`() {
        val token = api.register()
        confirm(request(token, "/api/v1/billing/pack/request", """{"months":1}""", 600_000))
        val today = eventStartingAt(token, Instant.now().plus(3, ChronoUnit.HOURS))
        val later = eventStartingAt(token, Instant.now().plus(20, ChronoUnit.DAYS))

        TenantContext.set(api.tenantId(token))
        organizerPack.recordCommitment(1_000)
        assertEquals(Long.MAX_VALUE, allowanceGate.allowanceCeiling(UUID.fromString(today), 0))
        assertEquals(0, allowanceGate.allowanceCeiling(UUID.fromString(later), 0))
        assertEquals(true, allowanceGate.interactiveCovered(UUID.fromString(later)))
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

    private fun request(
        token: String,
        path: String,
        body: String,
        expectedAmount: Int,
    ): String {
        val response =
            api
                .post(token, path, body)
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assertEquals(expectedAmount, JsonPath.read<Int>(response, "$.amountMinor"))
        return response
    }

    private fun confirm(instructions: String) {
        billingModuleApi.adminConfirmManualPayment(UUID.fromString(JsonPath.read(instructions, "$.paymentId")))
    }
}
