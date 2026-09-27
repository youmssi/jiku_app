package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.TestDates.EVENT_YEAR
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JIKU-34: sending beyond the unlocked allowance is blocked in full; unlocking a
 * paid tier via the JIKU-33 flow makes the same send succeed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "billing.free-tier-guests=2",
        "billing.payment.webhook-secret=test-paywall-secret",
    ],
)
class PaywallTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val secret = "test-paywall-secret"

    @Test
    fun `send beyond the allowance is blocked, then succeeds after paying`() {
        val token = register()
        val tenantId = tenantId(token)
        val eventId = createPublishedEvent(token)

        // Three guests, free-tier allowance of two: sending the batch is blocked.
        importGuests(token, eventId, listOf("Ada" to "One", "Bea" to "Two", "Cid" to "Three"))
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/invitations/send?channels=EMAIL")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isPaymentRequired())

        // Nothing was sent — the whole batch was refused, not partially delivered.
        assert(JsonPath.read<Int>(usage(token, eventId), "$.invitedGuests") == 0)

        // Pay for the BRONZE tier and confirm it via the signed callback.
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
        val reference = "$tenantId:${JsonPath.read<String>(payment, "$.paymentId")}"
        val body = """{"reference":"$reference","providerReference":"SANDBOX-x","status":"SUCCEEDED"}"""
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sign(body))
                    .content(body),
            ).andExpect(status().isOk())

        // The same send now succeeds.
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/invitations/send?channels=EMAIL")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assert(JsonPath.read<Int>(usage(token, eventId), "$.invitedGuests") == 3)
        }
    }

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

    private fun tenantId(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString,
            "$.tenantId",
        )

    private fun importGuests(
        token: String,
        eventId: String,
        rows: List<Pair<String, String>>,
    ) {
        val csv =
            buildString {
                appendLine("firstName,lastName,email,phone")
                rows.forEach { (f, l) -> appendLine("$f,$l,${f.lowercase()}@example.com,") }
            }
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
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
                            {"name":"Paywalled","timezone":"Africa/Abidjan",
                             "startDateTime":"${EVENT_YEAR}-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
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

    private fun register(): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Wall Org","email":"wall-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
}
