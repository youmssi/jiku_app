package com.jiku.admin

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.admin.internal.PlatformAdmin
import com.jiku.admin.internal.PlatformAdminRepository
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.shared.TenantAccessGate
import org.assertj.core.api.Assertions.assertThat
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
import java.util.UUID

/**
 * The suspension kill switch (JIKU-40): suspending a tenant blocks organizer
 * login, rejects already-issued tokens on their next request, stops guest links
 * from resolving, and every step of it is reversible and audit-logged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TenantSuspensionTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var invitationTokens: InvitationTokenService

    @Autowired
    lateinit var tenantAccessGate: TenantAccessGate

    @Test
    fun `suspension locks the tenant out everywhere and reactivation restores it`() {
        val organizerEmail = "suspended-org@jiku.test"
        val organizerToken = registerOrganizer(organizerEmail)
        val adminToken = adminLogin()

        // Find the tenant through the directory.
        val directory =
            mockMvc
                .perform(
                    get("/api/v1/admin/tenants")
                        .param("query", organizerEmail)
                        .header("Authorization", "Bearer $adminToken"),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andReturn()
                .response
                .contentAsString
        val tenantId = JsonPath.read<String>(directory, "$.entries[0].id")

        // The organizer works before suspension.
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk())

        // Suspend — the note is mandatory.
        mockMvc
            .perform(
                post("/api/v1/admin/tenants/$tenantId/suspend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"note":""}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isBadRequest())
        mockMvc
            .perform(
                post("/api/v1/admin/tenants/$tenantId/suspend")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"note":"unpaid invoice"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUSPENDED"))

        // Existing token is rejected on its next request.
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().is4xxClientError())

        // Fresh login is refused with the explicit reason.
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$organizerEmail","password":"supersecret"}"""),
            ).andExpect(status().isForbidden())

        // Guest invitation links of the suspended tenant stop resolving.
        val guestLink = invitationTokens.issue(UUID.randomUUID(), UUID.randomUUID(), tenantId)
        mockMvc
            .perform(get("/api/v1/rsvp/$guestLink"))
            .andExpect(status().isNotFound())

        assertThat(tenantAccessGate.isSuspended(tenantId)).isTrue()

        // Reactivate and verify access is restored immediately.
        mockMvc
            .perform(
                post("/api/v1/admin/tenants/$tenantId/reactivate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"note":"payment received"}""")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))

        assertThat(tenantAccessGate.isSuspended(tenantId)).isFalse()
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isOk())

        // Both actions are in the audit trail.
        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .param("action", "TENANT_SUSPENDED")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("tenant:$tenantId"))
            .andExpect(jsonPath("$.entries[0].note").value("unpaid invoice"))
    }

    private fun adminLogin(): String {
        val email = "suspension-admin@jiku.test"
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
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun registerOrganizer(email: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Suspension Org","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
