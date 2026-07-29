package com.jiku.billing

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.admin.internal.PlatformAdmin
import com.jiku.admin.internal.PlatformAdminRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * JIKU-41 end to end: an organizer requests a paid tier and receives transfer
 * instructions with a reference; re-requesting is idempotent; an admin confirming
 * the transfer unlocks the tier (same effect as a provider callback) and the
 * action lands in the audit log; rejecting leaves the allowance untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "billing.free-tier-guests=100",
        "billing.manual.payee-name=Jiku Operations",
        "billing.manual.mobile-money-number=+2250700000000",
        "billing.manual.mobile-money-operator=Orange Money",
    ],
)
class ManualPaymentFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `request instructions confirm unlocks and audit records the action`() {
        val token = register()
        val eventId = createPublishedEvent(token)
        val adminToken = adminLogin()

        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 100)

        // Request the BRONZE tier: pending payment with reference and payee details.
        val instructions =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments/manual")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.payee.payeeName").value("Jiku Operations"))
                .andExpect(jsonPath("$.payee.mobileMoneyNumber").value("+2250700000000"))
                .andReturn()
                .response.contentAsString
        val paymentId = JsonPath.read<String>(instructions, "$.paymentId")
        val reference = JsonPath.read<String>(instructions, "$.reference")
        assert(reference.startsWith("JK-"))

        // Re-requesting returns the same open request, not a duplicate.
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/payments/manual")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"tier":"BRONZE"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentId").value(paymentId))
            .andExpect(jsonPath("$.reference").value(reference))

        // Instructions stay retrievable (not a one-shot screen).
        mockMvc
            .perform(
                get("/api/v1/events/$eventId/payments/manual")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.reference").value(reference))

        // The request is on the admin desk; an organizer token is not allowed there.
        mockMvc
            .perform(get("/api/v1/admin/payments").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden())
        mockMvc
            .perform(
                get("/api/v1/admin/payments")
                    .param("status", "PENDING")
                    .param("provider", "manual")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '$paymentId')].reference").value(reference))

        // Confirm: transfer reference required, tier unlocks, audit trail written.
        mockMvc
            .perform(
                post("/api/v1/admin/payments/$paymentId/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"transactionReference":""}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isBadRequest())
        mockMvc
            .perform(
                post("/api/v1/admin/payments/$paymentId/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"transactionReference":"OM-TX-12345"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))

        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 300)

        // Confirming twice is refused.
        mockMvc
            .perform(
                post("/api/v1/admin/payments/$paymentId/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"transactionReference":"OM-TX-12345"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isConflict())

        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .param("action", "PAYMENT_CONFIRMED")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("payment:$paymentId"))
    }

    @Test
    fun `a rejected request leaves the allowance untouched`() {
        val token = register()
        val eventId = createPublishedEvent(token)
        val adminToken = adminLogin()

        val instructions =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments/manual")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"ARGENT"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        val paymentId = JsonPath.read<String>(instructions, "$.paymentId")

        mockMvc
            .perform(
                post("/api/v1/admin/payments/$paymentId/reject")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"no matching transfer received"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))

        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 100)

        // After rejection the organizer can request again — a fresh payment.
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/payments/manual")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"tier":"ARGENT"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    private fun adminLogin(): String {
        val email = "payments-admin@jiku.test"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode("admin-secret"))))
        }
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"admin-secret"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
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

    private fun createPublishedEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Concierge Event","timezone":"Africa/Abidjan",
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
                            """{"name":"Concierge Org","email":"manual-${UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
