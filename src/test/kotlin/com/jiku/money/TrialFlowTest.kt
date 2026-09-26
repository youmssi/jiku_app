package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.money.internal.TrialGrantRepository
import com.jiku.money.internal.TrialService
import com.jiku.money.internal.TrialStatus
import com.jiku.shared.TenantContext
import com.jiku.support.TestDates.EVENT_YEAR
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * JIKU-42: a granted trial raises the event's effective allowance without
 * touching the paid entitlement; ending or expiring it reverts the allowance;
 * a payment confirmed during the trial converts it so nothing is ever reverted
 * after money arrived.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TrialFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var trials: TrialGrantRepository

    @Autowired
    lateinit var trialService: TrialService

    @Test
    fun `a trial raises the allowance and ending it early reverts`() {
        val token = register()
        val tenantId = tenantId(token)
        val eventId = createPublishedEvent(token)
        val adminToken = adminLogin()

        assert(allowance(token, eventId) == 100)

        val trial =
            mockMvc
                .perform(
                    post("/api/v1/admin/trials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(tenantId, eventId, "BRONZE", Instant.now().plus(1, ChronoUnit.HOURS)))
                        .header("Authorization", "Bearer $adminToken"),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn()
                .response.contentAsString
        val trialId = JsonPath.read<String>(trial, "$.id")

        assert(allowance(token, eventId) == 300)

        // The admin listing resolves the tenant and event names (JIKU-99), and the
        // page envelope reports the true total, not just this page's row count.
        mockMvc
            .perform(get("/api/v1/admin/trials").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total", greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.entries[?(@.id=='$trialId')].tenantName", hasItem("Trial Org")))
            .andExpect(jsonPath("$.entries[?(@.id=='$trialId')].eventName", hasItem("Trial Event")))

        mockMvc
            .perform(get("/api/v1/admin/trials/stats").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active", greaterThanOrEqualTo(1)))

        // The event picker behind the grant form's combobox is scoped to the tenant.
        mockMvc
            .perform(
                get("/api/v1/admin/tenants/$tenantId/events")
                    .param("query", "Trial")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Trial Event"))

        // A second active trial for the same event is refused.
        mockMvc
            .perform(
                post("/api/v1/admin/trials")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(grantBody(tenantId, eventId, "ARGENT", Instant.now().plus(1, ChronoUnit.HOURS)))
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isConflict())

        // Ending early reverts the allowance and is audited.
        mockMvc
            .perform(
                post("/api/v1/admin/trials/$trialId/end")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"prospect went silent"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ENDED"))

        assert(allowance(token, eventId) == 100)

        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .param("action", "TRIAL_ENDED")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("trial:$trialId"))
    }

    @Test
    fun `the sweep reverts an unpaid trial but converts a paid one`() {
        val token = register()
        val tenantId = tenantId(token)
        val adminToken = adminLogin()

        // Unpaid: expiry reverts the allowance.
        val unpaidEvent = createPublishedEvent(token)
        val unpaidTrialId = grantTrial(adminToken, tenantId, unpaidEvent, "BRONZE")
        assert(allowance(token, unpaidEvent) == 300)
        forceExpiry(tenantId, unpaidTrialId)
        trialService.expireDue()
        assert(allowance(token, unpaidEvent) == 100)
        withTenant(tenantId) {
            assertThat(trials.findById(UUID.fromString(unpaidTrialId)).orElseThrow().status).isEqualTo(TrialStatus.EXPIRED)
        }

        // Paid during the trial: expiry converts, allowance stays (paid unlock).
        val paidEvent = createPublishedEvent(token)
        val paidTrialId = grantTrial(adminToken, tenantId, paidEvent, "BRONZE")
        val instructions =
            mockMvc
                .perform(
                    post("/api/v1/events/$paidEvent/payments/manual")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        val paymentId = JsonPath.read<String>(instructions, "$.paymentId")
        mockMvc
            .perform(
                post("/api/v1/admin/payments/$paymentId/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"transactionReference":"OM-TX-77777"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())

        forceExpiry(tenantId, paidTrialId)
        trialService.expireDue()
        assert(allowance(token, paidEvent) == 300)
        withTenant(tenantId) {
            assertThat(trials.findById(UUID.fromString(paidTrialId)).orElseThrow().status).isEqualTo(TrialStatus.CONVERTED)
        }
    }

    private fun grantTrial(
        adminToken: String,
        tenantId: String,
        eventId: String,
        tier: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/trials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(tenantId, eventId, tier, Instant.now().plus(1, ChronoUnit.HOURS)))
                        .header("Authorization", "Bearer $adminToken"),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.id")
    }

    private fun forceExpiry(
        tenantId: String,
        trialId: String,
    ) {
        withTenant(tenantId) {
            val trial = trials.findById(UUID.fromString(trialId)).orElseThrow()
            trial.expiresAt = Instant.now().minusSeconds(60)
            trials.save(trial)
        }
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

    private fun grantBody(
        tenantId: String,
        eventId: String,
        tier: String,
        expiresAt: Instant,
    ): String = """{"tenantId":"$tenantId","eventId":"$eventId","tier":"$tier","expiresAt":"$expiresAt"}"""

    private fun allowance(
        token: String,
        eventId: String,
    ): Int {
        val body =
            mockMvc
                .perform(get("/api/v1/events/$eventId/usage").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.allowance")
    }

    private fun tenantId(token: String): String {
        val body =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.tenantId")
    }

    private fun adminLogin(): String {
        val email = "trials-admin@jiku.test"
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

    private fun createPublishedEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Trial Event","timezone":"Africa/Abidjan",
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
                            """{"name":"Trial Org","email":"trial-${UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
