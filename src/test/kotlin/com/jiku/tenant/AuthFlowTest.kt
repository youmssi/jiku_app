package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * End-to-end auth flow: register, reject an unauthenticated protected call, log in,
 * access the protected endpoint with the access token, and refresh. Exercises the
 * JIKU-8 Definition of Done in one pass.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `register then login then access protected endpoint then refresh`() {
        val credentials = """{"email":"organizer@example.com","password":"supersecret"}"""

        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(credentials),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").exists())
            .andExpect(jsonPath("$.refreshToken").exists())

        // Protected endpoint is rejected without a token.
        mockMvc
            .perform(get("/api/v1/auth/me"))
            .andExpect(status().is4xxClientError())

        val loginBody =
            mockMvc
                .perform(
                    post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString

        val accessToken = JsonPath.read<String>(loginBody, "$.accessToken")
        val refreshToken = JsonPath.read<String>(loginBody, "$.refreshToken")

        // Protected endpoint succeeds with the access token and reflects the claims.
        mockMvc
            .perform(
                get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ORGANIZER_ADMIN"))
            .andExpect(jsonPath("$.tenantId").isNotEmpty())

        // Refresh issues a fresh access token.
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"refreshToken":"$refreshToken"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").exists())
    }
}
