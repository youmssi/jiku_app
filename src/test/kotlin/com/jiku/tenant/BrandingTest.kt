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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class BrandingTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `branding returns defaults then reflects updates`() {
        val token = register("Acme Events", "branding@acme.test")

        // Defaults: display name falls back to the org name, neutral primary color.
        mockMvc
            .perform(get("/api/v1/branding").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Acme Events"))
            .andExpect(jsonPath("$.primaryColor").value("#1E293B"))

        mockMvc
            .perform(
                put("/api/v1/branding")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"displayName":"Acme Live","logoUrl":"https://cdn.acme.test/logo.png","primaryColor":"#FF5722"}""",
                    ),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Acme Live"))

        // Updates persist.
        mockMvc
            .perform(get("/api/v1/branding").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logoUrl").value("https://cdn.acme.test/logo.png"))
            .andExpect(jsonPath("$.primaryColor").value("#FF5722"))

        // An invalid color is rejected.
        mockMvc
            .perform(
                put("/api/v1/branding")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"primaryColor":"red"}"""),
            ).andExpect(status().isBadRequest())
    }

    private fun register(
        name: String,
        email: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
