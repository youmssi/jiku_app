package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.shared.MemberInvitationNotice
import com.jiku.tenant.internal.OrganizerUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Member invitations end to end (JIKU-50): invite → email → accept → operate;
 * the role and last-owner rules; revocation; and removal cutting access on the
 * removed member's next request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, MemberInvitationFlowTest.NoticeRecorderConfig::class)
class MemberInvitationFlowTest {
    class NoticeRecorder {
        val notices = CopyOnWriteArrayList<MemberInvitationNotice>()

        @EventListener
        fun on(notice: MemberInvitationNotice) {
            notices += notice
        }

        fun lastTokenFor(email: String): String = URI(notices.last { it.email == email }.actionUrl).query.substringAfter("token=")
    }

    @TestConfiguration
    class NoticeRecorderConfig {
        @Bean
        fun invitationNoticeRecorder() = NoticeRecorder()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var recorder: NoticeRecorder

    @Autowired
    lateinit var users: OrganizerUserRepository

    @Test
    fun `invite, accept, operate, and lose access on removal`() {
        val ownerEmail = "invite-owner@jiku.test"
        val memberEmail = "invite-member@jiku.test"
        val ownerToken = registerVerifiedOwner(ownerEmail, "Invite Flow Org")

        // Invite with role MEMBER; re-inviting stays a single pending invitation.
        invite(ownerToken, memberEmail, "MEMBER")
        invite(ownerToken, memberEmail, "MEMBER")
        mockMvc
            .perform(get("/api/v1/members/invitations").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
        val inviteToken = recorder.lastTokenFor(memberEmail)

        // The public preview names the organization before any login.
        mockMvc
            .perform(get("/api/v1/auth/invitations/$inviteToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizationName").value("Invite Flow Org"))
            .andExpect(jsonPath("$.role").value("MEMBER"))

        // A different account cannot take the invitation.
        val strangerToken = registerAccount("invite-stranger@jiku.test")
        mockMvc
            .perform(
                post("/api/v1/auth/invitations/accept")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$inviteToken"}""")
                    .header("Authorization", "Bearer $strangerToken"),
            ).andExpect(status().isForbidden())

        // The invited address registers and accepts; tokens come back bound.
        val memberAccountToken = registerAccount(memberEmail)
        val memberOrgToken =
            JsonPath.read<String>(
                mockMvc
                    .perform(
                        post("/api/v1/auth/invitations/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"token":"$inviteToken"}""")
                            .header("Authorization", "Bearer $memberAccountToken"),
                    ).andExpect(status().isOk())
                    .andReturn()
                    .response
                    .contentAsString,
                "$.accessToken",
            )

        // The member operates events but cannot manage members; the token is single-use.
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $memberOrgToken"))
            .andExpect(status().isOk())
        mockMvc
            .perform(get("/api/v1/members").header("Authorization", "Bearer $memberOrgToken"))
            .andExpect(status().isForbidden())
        mockMvc
            .perform(
                post("/api/v1/auth/invitations/accept")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$inviteToken"}""")
                    .header("Authorization", "Bearer $memberAccountToken"),
            ).andExpect(status().isBadRequest())

        // Removal cuts the member's access on the very next request.
        val memberUserId =
            JsonPath.read<String>(
                mockMvc
                    .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $memberOrgToken"))
                    .andReturn()
                    .response
                    .contentAsString,
                "$.userId",
            )
        mockMvc
            .perform(delete("/api/v1/members/$memberUserId").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk())
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $memberOrgToken"))
            .andExpect(status().is4xxClientError())
    }

    @Test
    fun `role rules protect ownership and the last owner`() {
        val ownerEmail = "rules-owner@jiku.test"
        val adminEmail = "rules-admin@jiku.test"
        val ownerToken = registerVerifiedOwner(ownerEmail, "Rules Org")

        // OWNER cannot be handed out at the invitation door.
        mockMvc
            .perform(
                post("/api/v1/members/invitations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$adminEmail","role":"OWNER"}""")
                    .header("Authorization", "Bearer $ownerToken"),
            ).andExpect(status().isBadRequest())

        // Invite as ADMIN and accept.
        invite(ownerToken, adminEmail, "ADMIN")
        val adminAccountToken = registerAccount(adminEmail)
        val adminOrgToken =
            JsonPath.read<String>(
                mockMvc
                    .perform(
                        post("/api/v1/auth/invitations/accept")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"token":"${recorder.lastTokenFor(adminEmail)}"}""")
                            .header("Authorization", "Bearer $adminAccountToken"),
                    ).andExpect(status().isOk())
                    .andReturn()
                    .response
                    .contentAsString,
                "$.accessToken",
            )

        val ownerUserId = userId(ownerToken)
        val adminUserId = userId(adminOrgToken)

        // An ADMIN manages members but cannot grant ownership or demote the owner.
        mockMvc
            .perform(get("/api/v1/members").header("Authorization", "Bearer $adminOrgToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
        mockMvc
            .perform(
                put("/api/v1/members/$adminUserId/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"OWNER"}""")
                    .header("Authorization", "Bearer $adminOrgToken"),
            ).andExpect(status().isForbidden())
        mockMvc
            .perform(
                put("/api/v1/members/$ownerUserId/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"MEMBER"}""")
                    .header("Authorization", "Bearer $adminOrgToken"),
            ).andExpect(status().isForbidden())

        // The sole owner can neither demote themselves nor be removed.
        mockMvc
            .perform(
                put("/api/v1/members/$ownerUserId/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"MEMBER"}""")
                    .header("Authorization", "Bearer $ownerToken"),
            ).andExpect(status().isConflict())
        mockMvc
            .perform(delete("/api/v1/members/$ownerUserId").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isConflict())

        // The owner promotes the admin to OWNER; then stepping down works.
        mockMvc
            .perform(
                put("/api/v1/members/$adminUserId/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"OWNER"}""")
                    .header("Authorization", "Bearer $ownerToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("OWNER"))
        mockMvc
            .perform(
                put("/api/v1/members/$ownerUserId/role")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ADMIN"}""")
                    .header("Authorization", "Bearer $ownerToken"),
            ).andExpect(status().isOk())

        // Revocation: a revoked invitation cannot be accepted.
        invite(ownerToken, "rules-late@jiku.test", "MEMBER")
        val invitationId =
            JsonPath.read<String>(
                mockMvc
                    .perform(get("/api/v1/members/invitations").header("Authorization", "Bearer $ownerToken"))
                    .andReturn()
                    .response
                    .contentAsString,
                "$.[0].id",
            )
        mockMvc
            .perform(delete("/api/v1/members/invitations/$invitationId").header("Authorization", "Bearer $ownerToken"))
            .andExpect(status().isOk())
        val lateToken = registerAccount("rules-late@jiku.test")
        mockMvc
            .perform(
                post("/api/v1/auth/invitations/accept")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"${recorder.lastTokenFor("rules-late@jiku.test")}"}""")
                    .header("Authorization", "Bearer $lateToken"),
            ).andExpect(status().isBadRequest())
    }

    private fun invite(
        token: String,
        email: String,
        role: String,
    ) {
        mockMvc
            .perform(
                post("/api/v1/members/invitations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","role":"$role"}""")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isCreated())
    }

    private fun userId(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response
                .contentAsString,
            "$.userId",
        )

    /**
     * Registers with an org and marks the email verified directly — the emailed
     * verification round trip is AccountRecoveryTest's subject, not this one's.
     */
    private fun registerVerifiedOwner(
        email: String,
        orgName: String,
    ): String {
        val token =
            JsonPath.read<String>(
                registerBody("""{"name":"$orgName","email":"$email","password":"supersecret"}"""),
                "$.accessToken",
            )
        val user = requireNotNull(users.findByEmail(email))
        user.emailVerified = true
        users.saveAndFlush(user)
        return token
    }

    private fun registerAccount(email: String): String =
        JsonPath.read(registerBody("""{"email":"$email","password":"supersecret"}"""), "$.accessToken")

    private fun registerBody(json: String): String =
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json),
            ).andExpect(status().isCreated())
            .andReturn()
            .response
            .contentAsString
}
