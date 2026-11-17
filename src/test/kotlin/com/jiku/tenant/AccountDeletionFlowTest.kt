package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.GuestRepository
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import com.jiku.tenant.internal.AccountDeletionService
import com.jiku.tenant.internal.OrganizerMembership
import com.jiku.tenant.internal.OrganizerMembershipRepository
import com.jiku.tenant.internal.OrganizerRole
import com.jiku.tenant.internal.OrganizerUserRepository
import com.jiku.tenant.internal.TenantRepository
import com.jiku.tenant.internal.TenantStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * JIKU-316: deleting one's account is confirmed by the password, takes the
 * organizations owned alone only when agreed, can be undone by signing in
 * again, and at the end erases the account and those organizations' personal
 * data while leaving shared organizations untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AccountDeletionFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var deletion: AccountDeletionService

    @Autowired
    lateinit var users: OrganizerUserRepository

    @Autowired
    lateinit var memberships: OrganizerMembershipRepository

    @Autowired
    lateinit var tenants: TenantRepository

    @Autowired
    lateinit var guests: GuestRepository

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a sole owner must agree to delete the organization, and signing in again cancels`() {
        val token = api.register()
        val email = email(token)
        val tenantId = api.tenantId(token)

        api
            .get(token, "/api/v1/auth/me/deletion")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scheduledFor").doesNotExist())
            .andExpect(jsonPath("$.soleOwnedOrganizations[0].id").value(tenantId))
            .andExpect(jsonPath("$.confirmWithPassword").value(true))

        api.post(token, "/api/v1/auth/me/deletion", """{"password":"wrong-password"}""").andExpect(status().isUnauthorized())
        api
            .post(token, "/api/v1/auth/me/deletion", """{"password":"supersecret"}""")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("account.sole-owner"))

        api
            .post(token, "/api/v1/auth/me/deletion", """{"password":"supersecret","deleteOrganizations":true}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scheduledFor").exists())
        assertThat(tenant(tenantId)).isEqualTo(TenantStatus.CLOSING)
        api.get(token, "/api/v1/events").andExpect(status().isForbidden())

        login(email).andExpect(status().isOk())
        assertThat(tenant(tenantId)).isEqualTo(TenantStatus.ACTIVE)
        val fresh = JsonPath.read<String>(login(email).andReturn().response.contentAsString, "$.accessToken")
        api
            .get(fresh, "/api/v1/auth/me/deletion")
            .andExpect(jsonPath("$.scheduledFor").doesNotExist())
        api.get(fresh, "/api/v1/events").andExpect(status().isOk())
    }

    @Test
    fun `at the end the account and its organization's personal data are erased`() {
        val token = api.register()
        val email = email(token)
        val tenantId = api.tenantId(token)
        val userId =
            UUID.fromString(
                JsonPath.read(
                    api
                        .get(token, "/api/v1/auth/me")
                        .andReturn()
                        .response.contentAsString,
                    "$.userId",
                ),
            )
        val eventId = api.createEvent(token)
        val guestId = UUID.fromString(api.importGuest(token, eventId, "Aminata"))

        api
            .post(token, "/api/v1/auth/me/deletion", """{"password":"supersecret","deleteOrganizations":true}""")
            .andExpect(status().isOk())
        assertThat(deletion.runDue(Instant.now())).isZero()
        assertThat(deletion.runDue(Instant.now().plus(31, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1)

        assertThat(tenant(tenantId)).isEqualTo(TenantStatus.CLOSED)
        assertThat(memberships.findByTenantIdOrderByCreatedAtAsc(tenantId)).isEmpty()
        val user = users.findById(userId).orElseThrow()
        assertThat(user.email).isNotEqualTo(email).endsWith("@deleted.invalid")
        assertThat(user.passwordHash).isNull()
        assertThat(users.findByEmail(email)).isNull()
        TenantContext.withTenant(tenantId) {
            assertThat(guests.findById(guestId).orElseThrow().personalDataErased).isTrue()
        }
        login(email).andExpect(status().isUnauthorized())
    }

    @Test
    fun `an account that shares ownership leaves the organization running`() {
        val owner = api.register()
        val tenantId = api.tenantId(owner)
        val leaving = api.register()
        val leavingEmail = email(leaving)
        val leavingId =
            UUID.fromString(
                JsonPath.read(
                    api
                        .get(leaving, "/api/v1/auth/me")
                        .andReturn()
                        .response.contentAsString,
                    "$.userId",
                ),
            )
        val leavingOwnTenant = api.tenantId(leaving)
        memberships.save(OrganizerMembership(userId = leavingId, tenantId = tenantId, role = OrganizerRole.OWNER))

        api
            .get(leaving, "/api/v1/auth/me/deletion")
            .andExpect(jsonPath("$.soleOwnedOrganizations.length()").value(1))
            .andExpect(jsonPath("$.soleOwnedOrganizations[0].id").value(leavingOwnTenant))
        api
            .post(leaving, "/api/v1/auth/me/deletion", """{"password":"supersecret","deleteOrganizations":true}""")
            .andExpect(status().isOk())
        deletion.runDue(Instant.now().plus(31, ChronoUnit.DAYS))

        assertThat(tenant(tenantId)).isEqualTo(TenantStatus.ACTIVE)
        assertThat(tenant(leavingOwnTenant)).isEqualTo(TenantStatus.CLOSED)
        assertThat(memberships.findByTenantIdOrderByCreatedAtAsc(tenantId).map { it.userId }).doesNotContain(leavingId)
        api.get(owner, "/api/v1/events").andExpect(status().isOk())
        login(leavingEmail).andExpect(status().isUnauthorized())
    }

    private fun tenant(tenantId: String): TenantStatus = tenants.findById(UUID.fromString(tenantId)).orElseThrow().status

    private fun email(token: String): String =
        JsonPath.read(
            api
                .get(token, "/api/v1/auth/me")
                .andReturn()
                .response.contentAsString,
            "$.email",
        )

    private fun login(email: String): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"supersecret"}"""),
        )
}
