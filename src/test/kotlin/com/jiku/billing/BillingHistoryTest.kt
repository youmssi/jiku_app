package com.jiku.billing

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JIKU-35: a completed payment appears in the organizer's billing history and has a
 * downloadable receipt; another tenant never sees it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["billing.payment.webhook-secret=test-history-secret"])
class BillingHistoryTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val secret = "test-history-secret"

    @Test
    fun `a paid payment shows in history with a receipt and stays tenant-isolated`() {
        val token = register("hist")
        val tenantId = tenantId(token)
        val eventId = createPublishedEvent(token)

        val paymentId = pay(token, tenantId, eventId)

        // History lists the succeeded payment with its event and amount.
        mockMvc
            .perform(get("/api/v1/billing/payments").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].paymentId").value(paymentId))
            .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
            .andExpect(jsonPath("$[0].eventName").value("Paid Event"))
            .andExpect(jsonPath("$[0].tier").value("BRONZE"))

        // A plain-text receipt is available for the successful payment.
        mockMvc
            .perform(get("/api/v1/billing/payments/$paymentId/receipt").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(content().string(containsString("Payment receipt")))
            .andExpect(content().string(containsString("BRONZE")))

        // Another tenant sees none of it.
        val otherToken = register("other")
        mockMvc
            .perform(get("/api/v1/billing/payments").header("Authorization", "Bearer $otherToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))
        mockMvc
            .perform(get("/api/v1/billing/payments/$paymentId/receipt").header("Authorization", "Bearer $otherToken"))
            .andExpect(status().isNotFound())
    }

    private fun pay(
        token: String,
        tenantId: String,
        eventId: String,
    ): String {
        val payment =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        val paymentId = JsonPath.read<String>(payment, "$.paymentId")
        val body = """{"reference":"$tenantId:$paymentId","providerReference":"SANDBOX-x","status":"SUCCEEDED"}"""
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sign(body))
                    .content(body),
            ).andExpect(status().isOk())
        return paymentId
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun tenantId(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString,
            "$.tenantId",
        )

    private fun createPublishedEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Paid Event","timezone":"Africa/Abidjan",
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

    private fun register(prefix: String): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$prefix Org","email":"$prefix-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
}
