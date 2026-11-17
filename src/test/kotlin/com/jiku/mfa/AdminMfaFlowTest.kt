package com.jiku.mfa

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.shared.JwtService
import com.jiku.support.Authenticator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * JIKU-317: with the factor mandatory for the desk (the production default), a
 * password alone opens no desk session: an administrator without the factor sets
 * it up first, then every sign-in asks for a code, and a session from before the
 * factor cannot be renewed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["auth.mfa.admin-required=true"])
class AdminMfaFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var jwtService: JwtService

    @Test
    fun `an administrator sets up the factor before the desk opens, then gives a code each time`() {
        val email = newAdmin()
        val setupToken = challengeOf(login(email), "auth.mfa-setup-required")
        post("/api/v1/admin/auth/mfa/verify", """{"challengeToken":"$setupToken","code":"123456"}""")
            .andExpect(status().isUnauthorized())

        val secret =
            JsonPath.read<String>(
                post("/api/v1/admin/auth/mfa/setup", """{"challengeToken":"$setupToken"}""")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.otpauthUri").exists())
                    .andReturn()
                    .response.contentAsString,
                "$.secret",
            )
        val app = Authenticator(secret)
        val setup =
            post("/api/v1/admin/auth/mfa/setup/confirm", """{"challengeToken":"$setupToken","code":"${app.next()}"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backupCodes.length()").value(10))
                .andReturn()
                .response.contentAsString
        val access = JsonPath.read<String>(setup, "$.accessToken")
        mockMvc
            .perform(get("/api/v1/admin/me/mfa").header("Authorization", "Bearer $access"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))

        val challenge = challengeOf(login(email), "auth.mfa-required")
        post("/api/v1/admin/auth/mfa/setup", """{"challengeToken":"$challenge"}""").andExpect(status().isUnauthorized())
        post("/api/v1/admin/auth/mfa/verify", """{"challengeToken":"$challenge","code":"${app.next()}"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
    }

    @Test
    fun `a desk session from before the factor cannot be renewed`() {
        val email = newAdmin()
        val adminId = requireNotNull(admins.findByEmail(email)?.id).toString()
        val refresh = jwtService.generateRefreshToken(adminId, "", "PLATFORM_ADMIN")
        post("/api/v1/admin/auth/refresh", """{"refreshToken":"$refresh"}""").andExpect(status().isUnauthorized())
        assertThat(admins.findByEmail(email)).isNotNull()
    }

    private fun newAdmin(): String {
        val email = "mfa-${UUID.randomUUID()}@jiku.test"
        admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode("admin-secret"))))
        return email
    }

    private fun login(email: String): ResultActions = post("/api/v1/admin/auth/login", """{"email":"$email","password":"admin-secret"}""")

    private fun post(
        path: String,
        body: String,
    ): ResultActions = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun challengeOf(
        response: ResultActions,
        code: String,
    ): String =
        JsonPath.read(
            response
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(code))
                .andReturn()
                .response.contentAsString,
            "$.challengeToken",
        )
}
