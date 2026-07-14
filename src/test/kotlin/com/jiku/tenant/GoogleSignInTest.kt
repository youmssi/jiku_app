package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.tenant.internal.GoogleIdentity
import com.jiku.tenant.internal.GoogleIdentityVerifier
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException

/**
 * Google sign-in (JIKU-51) with the verifier faked — the cryptographic
 * verification against Google's JWKS is Nimbus's job; this covers the account
 * flow: find-or-create by verified email, linking to an existing password
 * account, verification-by-Google, and the password-login error for
 * Google-only accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, GoogleSignInTest.FakeVerifierConfig::class)
class GoogleSignInTest {
    @TestConfiguration
    class FakeVerifierConfig {
        @Bean
        @Primary
        fun fakeGoogleVerifier(): GoogleIdentityVerifier =
            object : GoogleIdentityVerifier {
                override fun isConfigured() = true

                override fun verify(idToken: String): GoogleIdentity =
                    when (idToken) {
                        "token-new" -> GoogleIdentity("google-new@jiku.test", true)
                        "token-existing" -> GoogleIdentity("google-existing@jiku.test", true)
                        "token-unverified" -> GoogleIdentity("google-unverified@jiku.test", false)
                        else -> throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token")
                    }
            }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a new Google identity gets an account, already verified, ready to create an org`() {
        val accessToken = googleSignIn("token-new")

        mockMvc
            .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("google-new@jiku.test"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.memberships.length()").value(0))

        // Google counts as email verification, so org creation is open.
        mockMvc
            .perform(
                post("/api/v1/orgs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Google Org"}""")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isCreated())

        // No password on the account: password login points at Google instead.
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"google-new@jiku.test","password":"whatever-123"}"""),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    fun `an existing password account links by email and keeps its organization`() {
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Linked Org","email":"google-existing@jiku.test","password":"supersecret"}"""),
            ).andExpect(status().isCreated())

        val accessToken = googleSignIn("token-existing")
        mockMvc
            .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ORGANIZER_OWNER"))
            .andExpect(jsonPath("$.memberships[0].tenantName").value("Linked Org"))

        // Both doors stay open on a linked account.
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"google-existing@jiku.test","password":"supersecret"}"""),
            ).andExpect(status().isOk())
    }

    @Test
    fun `bad and unverified Google identities are refused`() {
        mockMvc
            .perform(
                post("/api/v1/auth/google")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"idToken":"forged"}"""),
            ).andExpect(status().isUnauthorized())
        mockMvc
            .perform(
                post("/api/v1/auth/google")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"idToken":"token-unverified"}"""),
            ).andExpect(status().isForbidden())
    }

    private fun googleSignIn(idToken: String): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"idToken":"$idToken"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString,
            "$.accessToken",
        )
}
