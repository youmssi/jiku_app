package com.jiku.billing

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.billing.internal.UsageRecord
import com.jiku.billing.internal.UsageRecordRepository
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * JIKU-57: a manual payment request nets off any amount already prepaid toward
 * the event (a verified booking deposit that outgrew its estimated tier),
 * charging only the difference, and spends the credit so a second request
 * cannot reapply it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["billing.free-tier-guests=100"])
class PrepaymentDiscountTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var usageRecords: UsageRecordRepository

    @Test
    fun `a manual payment request charges only the amount beyond the prepaid credit, then spends it`() {
        val token = register()
        val tenantId = tenantId(token)
        val eventId = createPublishedEvent(token)

        withTenant(tenantId) {
            usageRecords.save(
                UsageRecord(eventId = java.util.UUID.fromString(eventId), unlockedAllowance = 100).also {
                    it.prepaidAmountMinor =
                        45_000
                },
            )
        }

        val first =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments/manual")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        // BRONZE is 150,000; 45,000 prepaid credit nets it down to 105,000.
        assert(JsonPath.read<Int>(first, "$.amountMinor") == 105_000)

        withTenant(tenantId) {
            val record = usageRecords.findByEventId(java.util.UUID.fromString(eventId))
            assert(record?.prepaidAmountMinor == 0L)
        }

        // A second event's request has no leftover credit to apply.
        val otherEvent = createPublishedEvent(token)
        val second =
            mockMvc
                .perform(
                    post("/api/v1/events/$otherEvent/payments/manual")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assert(JsonPath.read<Int>(second, "$.amountMinor") == 150_000)
    }

    private fun <T> withTenant(
        tenantId: String,
        block: () -> T,
    ): T {
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            return block()
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun tenantId(token: String): String {
        val body =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.tenantId")
    }

    private fun createPublishedEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Prepaid Event","timezone":"Africa/Abidjan",
                             "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        return eventId
    }

    private fun register(): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Prepaid Org","email":"prepaid-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
