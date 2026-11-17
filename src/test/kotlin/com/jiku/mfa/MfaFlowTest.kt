package com.jiku.mfa

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.shared.JwtService
import com.jiku.shared.SecurityNotice
import com.jiku.support.Authenticator
import com.jiku.support.OrganizerApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID

/**
 * JIKU-317: an organizer turns on a second factor; signing in then asks for a
 * code, which works once, a backup code works once, a remembered device skips
 * the step until it expires, and turning the factor off takes a valid code.
 * Sessions are listed and closed, and a new device is announced.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class MfaFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jwtService: JwtService

    @Autowired
    lateinit var events: ApplicationEvents

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a code is asked after the password, works once, and a backup code works once`() {
        val token = api.register()
        val email = email(token)
        val (app, backupCodes) = enable(token)
        assertThat(backupCodes).hasSize(10)
        api
            .get(
                token,
                "/api/v1/auth/me/mfa",
            ).andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.backupCodesLeft").value(10))

        val challenge = challengeOf(login(email))
        verify("""{"challengeToken":"$challenge","code":"${app.wrong()}"}""")
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("auth.mfa-invalid"))
        val code = app.next()
        val signedIn = verify("""{"challengeToken":"$challenge","code":"$code"}""").andExpect(status().isOk())
        val access = JsonPath.read<String>(signedIn.andReturn().response.contentAsString, "$.accessToken")
        api.get(access, "/api/v1/auth/me").andExpect(status().isOk())

        verify("""{"challengeToken":"${challengeOf(login(email))}","code":"$code"}""").andExpect(status().isUnauthorized())
        verify("""{"challengeToken":"${challengeOf(login(email))}","backupCode":"${backupCodes[0].lowercase()}"}""")
            .andExpect(status().isOk())
        verify("""{"challengeToken":"${challengeOf(login(email))}","backupCode":"${backupCodes[0]}"}""")
            .andExpect(status().isUnauthorized())
        verify("""{"challengeToken":"not-a-token","code":"${app.next()}"}""").andExpect(status().isUnauthorized())
    }

    @Test
    fun `a remembered device skips the code until it expires`() {
        val token = api.register()
        val email = email(token)
        val (app, _) = enable(token)
        val trusted =
            JsonPath.read<String>(
                verify("""{"challengeToken":"${challengeOf(login(email))}","code":"${app.next()}","trustDevice":true}""")
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.trustedDeviceToken",
            )
        login(email, trusted).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists())

        val userId =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/auth/me")
                    .andReturn()
                    .response.contentAsString,
                "$.userId",
            )
        val expiring = jwtService.sign(userId, mapOf("tokenType" to "mfa-device", "kind" to "ORGANIZER"), Duration.ofSeconds(1))
        Thread.sleep(1_500)
        login(email, expiring).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("auth.mfa-required"))
        login(email, "forged").andExpect(status().isUnauthorized())
    }

    @Test
    fun `turning the factor off takes a valid code`() {
        val token = api.register()
        val email = email(token)
        val (app, backupCodes) = enable(token)
        api.post(token, "/api/v1/auth/me/mfa/disable", """{"code":"${app.wrong()}"}""").andExpect(status().isUnauthorized())
        api.post(token, "/api/v1/auth/me/mfa/disable", """{"backupCode":"${backupCodes[1]}"}""").andExpect(status().isNoContent())
        login(email).andExpect(status().isOk())
        assertThat(notices(email).map { it.kind }).contains(SecurityNotice.KIND_MFA_ENABLED, SecurityNotice.KIND_MFA_DISABLED)
    }

    @Test
    fun `too many wrong codes are cut short`() {
        val token = api.register()
        val email = email(token)
        val (app, _) = enable(token)
        val challenge = challengeOf(login(email))
        repeat(8) { verify("""{"challengeToken":"$challenge","code":"${"%06d".format(it)}"}""") }
        verify("""{"challengeToken":"$challenge","code":"${app.next()}"}""").andExpect(status().isTooManyRequests())
    }

    @Test
    fun `sessions are listed, closed elsewhere, and a new device is announced`() {
        val token = api.register()
        val email = email(token)
        val laptop = tokensOf(login(email, device = "laptop-0000000000000001"))
        val phone = tokensOf(login(email, device = "phone-00000000000000002"))
        assertThat(notices(email).filter { it.kind == SecurityNotice.KIND_NEW_DEVICE }).hasSize(2)
        events.clear()
        login(email, device = "phone-00000000000000002")
        assertThat(notices(email).filter { it.kind == SecurityNotice.KIND_NEW_DEVICE }).isEmpty()

        api
            .get(laptop.first, "/api/v1/auth/me/sessions")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.current == true)].device").value("Test browser"))
        api.post(laptop.first, "/api/v1/auth/me/sessions/close-others", "{}").andExpect(status().isNoContent())

        refresh(phone.second).andExpect(status().isUnauthorized())
        mockMvc
            .perform(get("/api/v1/auth/me").header("Authorization", "Bearer ${phone.first}"))
            .andExpect { assertThat(it.response.status).isIn(401, 403) }
        refresh(laptop.second).andExpect(status().isOk())
        api.get(laptop.first, "/api/v1/auth/me").andExpect(status().isOk())
    }

    private fun enable(token: String): Pair<Authenticator, List<String>> {
        val secret =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/auth/me/mfa/setup", "{}")
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.secret",
            )
        val app = Authenticator(secret)
        api.post(token, "/api/v1/auth/me/mfa/confirm", """{"code":"${app.wrong()}"}""").andExpect(status().isUnauthorized())
        val codes =
            JsonPath.read<List<String>>(
                api
                    .post(token, "/api/v1/auth/me/mfa/confirm", """{"code":"${app.next()}"}""")
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.backupCodes",
            )
        return app to codes
    }

    private fun email(token: String): String =
        JsonPath.read(
            api
                .get(token, "/api/v1/auth/me")
                .andReturn()
                .response.contentAsString,
            "$.email",
        )

    private fun notices(email: String): List<SecurityNotice> =
        events.stream(SecurityNotice::class.java).filter { it.email == email }.toList()

    private fun login(
        email: String,
        trustedDeviceToken: String? = null,
        device: String = "device-${UUID.randomUUID()}".take(40),
    ): ResultActions {
        val trusted = trustedDeviceToken?.let { ""","trustedDeviceToken":"$it"""" }.orEmpty()
        return mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"supersecret","deviceId":"$device","device":"Test browser"$trusted}"""),
        )
    }

    private fun verify(body: String): ResultActions =
        mockMvc.perform(post("/api/v1/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun refresh(refreshToken: String): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"$refreshToken"}"""),
        )

    private fun challengeOf(response: ResultActions): String =
        JsonPath.read(
            response
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.mfa-required"))
                .andReturn()
                .response.contentAsString,
            "$.challengeToken",
        )

    private fun tokensOf(response: ResultActions): Pair<String, String> {
        val body =
            response
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read<String>(body, "$.accessToken") to JsonPath.read(body, "$.refreshToken")
    }
}
