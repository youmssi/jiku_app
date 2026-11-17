package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.shared.SecurityNotice
import com.jiku.support.Authenticator
import com.jiku.support.OrganizerApi
import com.jiku.support.PasskeyDevice
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * JIKU-318: an organizer adds a passkey from the security settings, then signs
 * in with it alone, even with an authentication app on; a ceremony works once,
 * and a passkey can be renamed and removed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class PasskeyFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var events: ApplicationEvents

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a passkey is added, signs in without password or code, and can be renamed and removed`() {
        val token = api.register()
        val email =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/auth/me")
                    .andReturn()
                    .response.contentAsString,
                "$.email",
            )
        val phone = PasskeyDevice()

        val registration =
            api
                .post(
                    token,
                    "/api/v1/auth/me/passkeys/options",
                    "{}",
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assertThat(JsonPath.read<String>(registration, "$.options.authenticatorSelection.residentKey")).isEqualTo("required")
        val ceremonyId = JsonPath.read<String>(registration, "$.ceremonyId")
        val credential = phone.create(registration)
        val passkeyId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/auth/me/passkeys",
                        """{"ceremonyId":"$ceremonyId","credential":$credential,"label":"Mon téléphone"}""",
                    ).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.label").value("Mon téléphone"))
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api
            .post(
                token,
                "/api/v1/auth/me/passkeys",
                """{"ceremonyId":"$ceremonyId","credential":$credential,"label":"Encore"}""",
            ).andExpect(status().isUnauthorized())
        assertThat(
            events.stream(SecurityNotice::class.java).filter {
                it.email == email && it.kind == SecurityNotice.KIND_PASSKEY_ADDED
            },
        ).hasSize(1)

        // An authentication app on the account does not add a code to a passkey sign-in.
        enableApp(token)
        val signIn = start()
        val assertion = phone.sign(signIn)
        finish(signIn, assertion).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").exists())
        finish(signIn, assertion).andExpect(status().isUnauthorized())

        api
            .patch(token, "/api/v1/auth/me/passkeys/$passkeyId", """{"label":"Téléphone de bureau"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("Téléphone de bureau"))
        api
            .get(token, "/api/v1/auth/me/passkeys")
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].lastUsedAt").exists())
        mockMvc
            .perform(delete("/api/v1/auth/me/passkeys/$passkeyId").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent())
        val again = start()
        finish(again, phone.sign(again)).andExpect(status().isUnauthorized())
    }

    @Test
    fun `another account cannot touch a passkey`() {
        val owner = api.register()
        val phone = PasskeyDevice()
        val registration =
            api
                .post(owner, "/api/v1/auth/me/passkeys/options", "{}")
                .andReturn()
                .response.contentAsString
        val id =
            JsonPath.read<String>(
                api
                    .post(
                        owner,
                        "/api/v1/auth/me/passkeys",
                        """{"ceremonyId":"${JsonPath.read<String>(
                            registration,
                            "$.ceremonyId",
                        )}","credential":${phone.create(registration)},"label":"A"}""",
                    ).andReturn()
                    .response.contentAsString,
                "$.id",
            )
        val other = api.register()
        api.patch(other, "/api/v1/auth/me/passkeys/$id", """{"label":"B"}""").andExpect(status().isNotFound())
        api.get(other, "/api/v1/auth/me/passkeys").andExpect(jsonPath("$.length()").value(0))
        // A ceremony opened for one account cannot finish for another.
        val stolen =
            api
                .post(owner, "/api/v1/auth/me/passkeys/options", "{}")
                .andReturn()
                .response.contentAsString
        api
            .post(
                other,
                "/api/v1/auth/me/passkeys",
                """{"ceremonyId":"${JsonPath.read<String>(
                    stolen,
                    "$.ceremonyId",
                )}","credential":${PasskeyDevice().create(stolen)},"label":"C"}""",
            ).andExpect(status().isUnauthorized())
    }

    private fun enableApp(token: String) {
        val secret =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/auth/me/mfa/setup", "{}")
                    .andReturn()
                    .response.contentAsString,
                "$.secret",
            )
        api.post(token, "/api/v1/auth/me/mfa/confirm", """{"code":"${Authenticator(secret).next()}"}""").andExpect(status().isOk())
    }

    private fun start(): String =
        mockMvc
            .perform(post("/api/v1/auth/passkey/options").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.options.userVerification").value("required"))
            .andReturn()
            .response.contentAsString

    private fun finish(
        ceremony: String,
        assertion: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/passkey")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"ceremonyId":"${JsonPath.read<String>(ceremony, "$.ceremonyId")}","credential":$assertion}"""),
        )
}
