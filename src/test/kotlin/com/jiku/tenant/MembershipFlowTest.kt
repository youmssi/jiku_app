package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.TestDates.EVENT_YEAR
import com.jiku.tenant.internal.OrganizerMembership
import com.jiku.tenant.internal.OrganizerMembershipRepository
import com.jiku.tenant.internal.OrganizerRole
import com.jiku.tenant.internal.OrganizerUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * The JIKU-48 membership model end to end: an account exists before any
 * organization, organizations are created (and switched) explicitly, data stays
 * isolated per organization, and the MEMBER role operates events without
 * reaching organization management.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class MembershipFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var users: OrganizerUserRepository

    @Autowired
    lateinit var memberships: OrganizerMembershipRepository

    @Test
    fun `account first, then organizations, with isolation and switching`() {
        // Register without an organization: the account is unbound.
        val unboundToken = register("multi-org@jiku.test")
        mockMvc
            .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $unboundToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.tenantId").value(""))
            .andExpect(jsonPath("$.memberships.length()").value(0))

        // Unbound accounts cannot reach organizer endpoints.
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $unboundToken"))
            .andExpect(status().isForbidden())

        // Organization creation is gated on a verified email (JIKU-49).
        markVerified("multi-org@jiku.test")

        // Create the first organization; the returned tokens are bound to it.
        val orgAToken = createOrg(unboundToken, "First Org")
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $orgAToken"))
            .andExpect(status().isOk())
        val eventId = createEvent(orgAToken, "Board Meeting")

        // A second organization starts empty: full isolation between the two.
        val orgBToken = createOrg(orgAToken, "Second Org")
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $orgBToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))
        mockMvc
            .perform(get("/api/v1/events/$eventId").header("Authorization", "Bearer $orgBToken"))
            .andExpect(status().isNotFound())

        // Both memberships are visible, and switching back restores the first org's data.
        val me =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $orgBToken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(2))
                .andReturn()
                .response
                .contentAsString
        val orgATenantId =
            JsonPath.read<List<String>>(me, "$.memberships[?(@.tenantName == 'First Org')].tenantId").first()

        val switchedToken =
            JsonPath.read<String>(
                mockMvc
                    .perform(
                        post("/api/v1/auth/switch-org")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"tenantId":"$orgATenantId"}""")
                            .header("Authorization", "Bearer $orgBToken"),
                    ).andExpect(status().isOk())
                    .andReturn()
                    .response
                    .contentAsString,
                "$.accessToken",
            )
        mockMvc
            .perform(get("/api/v1/events/$eventId").header("Authorization", "Bearer $switchedToken"))
            .andExpect(status().isOk())

        // Switching to an organization the user is not a member of is refused.
        val strangerToken = register("stranger@jiku.test")
        mockMvc
            .perform(
                post("/api/v1/auth/switch-org")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"tenantId":"$orgATenantId"}""")
                    .header("Authorization", "Bearer $strangerToken"),
            ).andExpect(status().isForbidden())
    }

    @Test
    fun `a MEMBER operates events but cannot touch organization management`() {
        val ownerToken = register("member-flow-owner@jiku.test", orgName = "Member Flow Org")
        val orgTenantId =
            JsonPath.read<String>(
                mockMvc
                    .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $ownerToken"))
                    .andReturn()
                    .response
                    .contentAsString,
                "$.tenantId",
            )

        // Until invitations exist (JIKU-50) the membership is planted directly.
        register("member-flow-member@jiku.test")
        val memberUser = requireNotNull(users.findByEmail("member-flow-member@jiku.test"))
        memberships.saveAndFlush(
            OrganizerMembership(
                userId = requireNotNull(memberUser.id),
                tenantId = orgTenantId,
                role = OrganizerRole.MEMBER,
            ),
        )

        // Login binds the fresh membership.
        val memberToken =
            JsonPath.read<String>(
                mockMvc
                    .perform(
                        post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"email":"member-flow-member@jiku.test","password":"supersecret"}"""),
                    ).andExpect(status().isOk())
                    .andReturn()
                    .response
                    .contentAsString,
                "$.accessToken",
            )

        // The member operates events inside the shared organization…
        val eventId = createEvent(ownerToken, "Team Offsite")
        mockMvc
            .perform(get("/api/v1/events/$eventId").header("Authorization", "Bearer $memberToken"))
            .andExpect(status().isOk())

        // …but organization management stays out of reach.
        mockMvc
            .perform(
                put("/api/v1/branding")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"displayName":"Rebranded"}""")
                    .header("Authorization", "Bearer $memberToken"),
            ).andExpect(status().isForbidden())
        mockMvc
            .perform(get("/api/v1/settings/providers").header("Authorization", "Bearer $memberToken"))
            .andExpect(status().isForbidden())

        // The owner keeps full access to the same surfaces.
        mockMvc
            .perform(get("/api/v1/settings/providers").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk())
    }

    private fun markVerified(email: String) {
        val user = requireNotNull(users.findByEmail(email))
        user.emailVerified = true
        users.saveAndFlush(user)
    }

    private fun register(
        email: String,
        orgName: String? = null,
    ): String {
        val name = orgName?.let { """"name":"$it",""" }.orEmpty()
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{$name"email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun createOrg(
        token: String,
        name: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name"}""")
                        .header("Authorization", "Bearer $token"),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun createEvent(
        token: String,
        name: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","timezone":"Africa/Abidjan","startDateTime":"${EVENT_YEAR}-12-31T19:00:00Z","invitationChannels":["EMAIL"]}""",
                        ).header("Authorization", "Bearer $token"),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.id")
    }
}
