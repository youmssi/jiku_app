package com.jiku.backoffice

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.AgreementService
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
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
 * JIKU-43: an agreement's lifecycle — created with a validated period, renewed
 * into a fresh row with the old period preserved, interrupted with the tenant
 * kill switch for SaaS deals, and expired by the sweep once the period ends.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AgreementFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var agreementService: AgreementService

    @Test
    fun `create renew and interrupt with the SaaS interruption suspending the tenant`() {
        val organizerEmail = "agreement-org@jiku.test"
        registerOrganizer(organizerEmail)
        val adminToken = adminLogin()
        val tenantId = findTenantId(adminToken, organizerEmail)

        // An unknown tenant is refused; a bad period is refused.
        mockMvc
            .perform(
                post("/api/v1/admin/agreements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(UUID.randomUUID().toString(), "ENTERPRISE_SAAS", days(0), days(365)))
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isNotFound())
        mockMvc
            .perform(
                post("/api/v1/admin/agreements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(tenantId, "ENTERPRISE_SAAS", days(365), days(0)))
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isBadRequest())

        val created =
            mockMvc
                .perform(
                    post("/api/v1/admin/agreements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(tenantId, "ENTERPRISE_SAAS", days(0), days(365)))
                        .header("Authorization", "Bearer $adminToken"),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn()
                .response.contentAsString
        val agreementId = JsonPath.read<String>(created, "$.id")

        // A second active agreement of the same kind is refused.
        mockMvc
            .perform(
                post("/api/v1/admin/agreements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(tenantId, "ENTERPRISE_SAAS", days(0), days(365)))
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isConflict())

        // Renewal closes the old row and opens the next period.
        val renewed =
            mockMvc
                .perform(
                    post("/api/v1/admin/agreements/$agreementId/renew")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"periodEnd":"${days(730)}"}""")
                        .header("Authorization", "Bearer $adminToken"),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn()
                .response.contentAsString
        val renewedId = JsonPath.read<String>(renewed, "$.id")

        mockMvc
            .perform(
                get("/api/v1/admin/agreements")
                    .param("status", "RENEWED")
                    .param("tenantId", tenantId)
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(agreementId))
            .andExpect(jsonPath("$[0].renewedBy").value(renewedId))

        // Interrupting the SaaS agreement suspends the tenant.
        mockMvc
            .perform(
                post("/api/v1/admin/agreements/$renewedId/interrupt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"invoice unpaid after grace"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INTERRUPTED"))

        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$organizerEmail","password":"supersecret"}"""),
            ).andExpect(status().isForbidden())

        // Both lifecycle actions are in the audit trail.
        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .param("action", "AGREEMENT_INTERRUPTED")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("agreement:$renewedId"))
    }

    @Test
    fun `the sweep expires a past-end agreement without touching the tenant`() {
        val organizerEmail = "expiring-org@jiku.test"
        registerOrganizer(organizerEmail)
        val adminToken = adminLogin()
        val tenantId = findTenantId(adminToken, organizerEmail)

        val created =
            mockMvc
                .perform(
                    post("/api/v1/admin/agreements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(tenantId, "ON_PREMISE", days(-30), days(1)))
                        .header("Authorization", "Bearer $adminToken"),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        val agreementId = JsonPath.read<String>(created, "$.id")

        // Not yet due: nothing changes. Past the end: EXPIRED.
        agreementService.expireDue(Instant.now())
        mockMvc
            .perform(get("/api/v1/admin/agreements").param("tenantId", tenantId).header("Authorization", "Bearer $adminToken"))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))

        agreementService.expireDue(Instant.now().plus(2, ChronoUnit.DAYS))
        mockMvc
            .perform(get("/api/v1/admin/agreements").param("tenantId", tenantId).header("Authorization", "Bearer $adminToken"))
            .andExpect(jsonPath("$[0].id").value(agreementId))
            .andExpect(jsonPath("$[0].status").value("EXPIRED"))

        // Expiry alerts, it does not suspend: the organizer still logs in.
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$organizerEmail","password":"supersecret"}"""),
            ).andExpect(status().isOk())
    }

    private fun createBody(
        tenantId: String,
        kind: String,
        start: Instant,
        end: Instant,
    ): String =
        """
        {"tenantId":"$tenantId","kind":"$kind","periodStart":"$start","periodEnd":"$end",
         "amountMinor":50000000,"currency":"XOF","notes":"negotiated launch deal"}
        """.trimIndent()

    private fun days(offset: Long): Instant = Instant.now().plus(offset, ChronoUnit.DAYS)

    private fun findTenantId(
        adminToken: String,
        organizerEmail: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    get("/api/v1/admin/tenants")
                        .param("query", organizerEmail)
                        .header("Authorization", "Bearer $adminToken"),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.entries[0].id")
    }

    private fun adminLogin(): String {
        val email = "agreements-admin@jiku.test"
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

    private fun registerOrganizer(email: String) {
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Agreement Org","email":"$email","password":"supersecret"}"""),
            ).andExpect(status().isCreated())
    }
}
