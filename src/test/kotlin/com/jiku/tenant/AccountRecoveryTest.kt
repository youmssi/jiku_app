package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.shared.AccountNotice
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Password reset and email verification (JIKU-49): tokens are single-use and
 * mailed as links, a reset cuts off old refresh tokens, nothing reveals whether
 * an address has an account, and organization creation waits for verification.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, AccountRecoveryTest.NoticeRecorderConfig::class)
class AccountRecoveryTest {
    class NoticeRecorder {
        val notices = CopyOnWriteArrayList<AccountNotice>()

        @EventListener
        fun on(notice: AccountNotice) {
            notices += notice
        }

        fun lastTokenFor(
            email: String,
            kind: String,
        ): String {
            val notice = notices.last { it.email == email && it.kind == kind }
            val query = URI(notice.actionUrl).query
            return query.substringAfter("token=")
        }
    }

    @TestConfiguration
    class NoticeRecorderConfig {
        @Bean
        fun noticeRecorder() = NoticeRecorder()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var recorder: NoticeRecorder

    @Test
    fun `forgot-password stays mute about unknown addresses`() {
        mockMvc
            .perform(
                post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"nobody-here@jiku.test"}"""),
            ).andExpect(status().isOk())
        assertThat(recorder.notices.none { it.email == "nobody-here@jiku.test" }).isTrue()
    }

    @Test
    fun `password reset round trip cuts off the old credentials`() {
        val email = "reset-flow@jiku.test"
        val refreshToken = register(email, orgName = "Reset Flow Org")

        mockMvc
            .perform(
                post("/api/v1/auth/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email"}"""),
            ).andExpect(status().isOk())
        val token = recorder.lastTokenFor(email, AccountNotice.KIND_PASSWORD_RESET)

        mockMvc
            .perform(
                post("/api/v1/auth/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$token","password":"brand-new-secret"}"""),
            ).andExpect(status().isOk())

        // Old password refused, new one accepted.
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"supersecret"}"""),
            ).andExpect(status().isUnauthorized())
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email","password":"brand-new-secret"}"""),
            ).andExpect(status().isOk())

        // Refresh tokens issued before the reset are dead.
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"refreshToken":"$refreshToken"}"""),
            ).andExpect(status().isUnauthorized())

        // The reset link is strictly single-use.
        mockMvc
            .perform(
                post("/api/v1/auth/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$token","password":"yet-another-secret"}"""),
            ).andExpect(status().isBadRequest())
    }

    @Test
    fun `email verification gates organization creation`() {
        val email = "verify-flow@jiku.test"
        val accessToken = registerAccessToken(email)

        // Unverified accounts cannot create an organization.
        mockMvc
            .perform(
                post("/api/v1/orgs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Too Early Org"}""")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isForbidden())

        // Resend works and replaces the registration token; verify with the new one.
        mockMvc
            .perform(post("/api/v1/auth/verify-email/resend").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
        val token = recorder.lastTokenFor(email, AccountNotice.KIND_EMAIL_VERIFICATION)
        mockMvc
            .perform(
                post("/api/v1/auth/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"token":"$token"}"""),
            ).andExpect(status().isOk())

        mockMvc
            .perform(
                post("/api/v1/orgs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Verified Org"}""")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isCreated())
    }

    private fun register(
        email: String,
        orgName: String? = null,
    ): String = JsonPath.read(registerBody(email, orgName), "$.refreshToken")

    private fun registerAccessToken(email: String): String = JsonPath.read(registerBody(email, null), "$.accessToken")

    private fun registerBody(
        email: String,
        orgName: String?,
    ): String {
        val name = orgName?.let { """"name":"$it",""" }.orEmpty()
        return mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{$name"email":"$email","password":"supersecret"}"""),
            ).andExpect(status().isCreated())
            .andReturn()
            .response
            .contentAsString
    }
}
