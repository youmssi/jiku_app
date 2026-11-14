package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.money.internal.ManualPaymentService
import com.jiku.support.TestDates.EVENT_YEAR
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * JIKU-41 end to end: an organizer requests paid guests and receives transfer
 * instructions with a reference; re-requesting is idempotent; an admin confirming
 * the transfer unlocks the guests (same effect as a provider callback) and the
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

    @Autowired
    lateinit var manualPayments: ManualPaymentService

    @Test
    fun `request instructions confirm unlocks and audit records the action`() {
        val token = register()
        val eventId = createPublishedEvent(token)
        val adminToken = adminLogin()

        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 100)

        // Request 300 guests: pending payment with reference and payee details.
        val instructions =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments/manual")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"guests":300}"""),
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
                    .content("""{"guests":300}"""),
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

        // Confirm: transfer reference required, guests unlock, audit trail written.
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
                        .content("""{"guests":600}"""),
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
        assert(
            JsonPath.read<List<String>>(history(token), "$[?(@.paymentId == '$paymentId')].rejectionReason").single() ==
                "no matching transfer received",
        )

        // After rejection the organizer can request again — a fresh payment.
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/payments/manual")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"guests":600}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    @Test
    fun `waiting requests are listed, replaced, cancelled and still confirmable when the money arrives`() {
        val token = register()
        val eventId = createPublishedEvent(token)
        val adminToken = adminLogin()

        val first = requestGuests(token, eventId, 300)
        val second = requestGuests(token, eventId, 400)
        assert(first != second) { "a request for other guests replaces the waiting one" }

        // One list of what waits for a transfer: only the newer request.
        mockMvc
            .perform(get("/api/v1/billing/payment-requests").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].paymentId").value(second))
            .andExpect(jsonPath("$[0].kind").value("GUESTS"))
            .andExpect(jsonPath("$[0].eventName").value("Concierge Event"))
            .andExpect(jsonPath("$[0].expiresAt").exists())
        assert(JsonPath.read<List<String>>(history(token), "$[?(@.paymentId == '$first')].status").single() == "CANCELLED")

        // Saying the transfer is sent marks the request, once, for the desk.
        mockMvc
            .perform(post("/api/v1/billing/payment-requests/$second/transfer-sent").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transferDeclaredAt").exists())
        mockMvc
            .perform(
                get("/api/v1/admin/payments")
                    .param("provider", "manual")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '$second')].transferDeclaredAt").exists())

        // The organizer cancels it; cancelling again is refused.
        mockMvc
            .perform(post("/api/v1/billing/payment-requests/$second/cancel").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
        mockMvc
            .perform(post("/api/v1/billing/payment-requests/$second/cancel").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict())
        mockMvc
            .perform(get("/api/v1/billing/payment-requests").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$.length()").value(0))

        // Another organization cannot touch it.
        mockMvc
            .perform(post("/api/v1/billing/payment-requests/$first/cancel").header("Authorization", "Bearer ${register()}"))
            .andExpect(status().isNotFound())

        // The transfer arrives after all: the desk can still confirm it.
        confirm(adminToken, second).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 400)
    }

    @Test
    fun `a request with no transfer expires, and the desk can still confirm it`() {
        val token = register()
        val eventId = createPublishedEvent(token)
        val adminToken = adminLogin()
        val paymentId = requestGuests(token, eventId, 250)

        assert(manualPayments.expireStale(Instant.now().plus(Duration.ofDays(8))) >= 1)

        mockMvc
            .perform(get("/api/v1/billing/payment-requests").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$.length()").value(0))
        assert(JsonPath.read<List<String>>(history(token), "$[?(@.paymentId == '$paymentId')].status").single() == "EXPIRED")
        mockMvc
            .perform(post("/api/v1/billing/payment-requests/$paymentId/transfer-sent").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict())

        confirm(adminToken, paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
        assert(JsonPath.read<Int>(usage(token, eventId), "$.allowance") == 250)
    }

    private fun requestGuests(
        token: String,
        eventId: String,
        guests: Int,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments/manual")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"guests":$guests}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.paymentId")
    }

    private fun confirm(
        adminToken: String,
        paymentId: String,
    ) = mockMvc.perform(
        post("/api/v1/admin/payments/$paymentId/confirm")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"transactionReference":"OM-TX-${UUID.randomUUID()}"}""")
            .header("Authorization", "Bearer $adminToken"),
    )

    private fun history(token: String): String =
        mockMvc
            .perform(get("/api/v1/billing/payments").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andReturn()
            .response.contentAsString

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
