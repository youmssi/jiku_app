package com.jiku.billing

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JIKU-33: a Mobile Money payment unlocks an event's usage tier only when confirmed
 * server-to-server via the signature-verified callback; an unsigned/failed payment
 * leaves the allowance unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "billing.free-tier-guests=100",
        "billing.payment.webhook-secret=test-payment-secret",
    ],
)
class PaymentTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val secret = "test-payment-secret"

    @Test
    fun `a confirmed payment unlocks the tier, while unsigned and failed callbacks do not`() {
        val token = register()
        val tenantId = tenantId(token)
        val eventId = createPublishedEvent(token)

        // Baseline: free tier allowance.
        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 100)

        // Initiate a BRONZE payment: recorded PENDING with a client instruction.
        val initiation =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.instruction.type").value("REDIRECT"))
                .andReturn()
                .response.contentAsString
        val paymentId = JsonPath.read<String>(initiation, "$.paymentId")
        val reference = "$tenantId:$paymentId"

        // An unsigned callback is rejected and nothing is unlocked.
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(callbackBody(reference, "SUCCEEDED")),
            ).andExpect(status().isUnauthorized())
        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 100)

        // A properly signed success callback unlocks the BRONZE allowance (300).
        val body = callbackBody(reference, "SUCCEEDED")
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sign(body))
                    .content(body),
            ).andExpect(status().isOk())
        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 300)

        // A second event's failed payment leaves that event on the free tier.
        val otherEvent = createPublishedEvent(token)
        val other =
            mockMvc
                .perform(
                    post("/api/v1/events/$otherEvent/payments")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andReturn()
                .response.contentAsString
        val otherRef = "$tenantId:${JsonPath.read<String>(other, "$.paymentId")}"
        val failedBody = callbackBody(otherRef, "FAILED")
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sign(failedBody))
                    .content(failedBody),
            ).andExpect(status().isOk())
        assert(JsonPath.read<Int>(usage(token, otherEvent), "$.allowance") == 100)
    }

    private fun callbackBody(
        reference: String,
        outcome: String,
    ): String = """{"reference":"$reference","providerReference":"SANDBOX-x","status":"$outcome"}"""

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun usage(
        token: String,
        eventId: String,
    ): String =
        mockMvc
            .perform(get("/api/v1/events/$eventId/usage").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andReturn()
            .response.contentAsString

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

    private fun register(): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Pay Org","email":"pay-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
