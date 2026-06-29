package com.jiku.notification

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ReputationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `webhook rejects a bad secret`() {
        mockMvc
            .perform(
                post("/api/v1/notifications/email-feedback")
                    .header("X-Webhook-Secret", "wrong")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"events":[{"recipient":"x@example.com","type":"HARD_BOUNCE"}]}"""),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    fun `repeated hard bounces flag an address as undeliverable on import`() {
        val bounced = "bouncer@example.com"
        // Two hard bounces cross the undeliverable threshold (no prior send needed —
        // reputation is tracked globally).
        mockMvc
            .perform(
                post("/api/v1/notifications/email-feedback")
                    .header("X-Webhook-Secret", "local-development-webhook-secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"events":[
                            {"recipient":"$bounced","type":"HARD_BOUNCE"},
                            {"recipient":"$bounced","type":"HARD_BOUNCE"}
                        ]}""",
                    ),
            ).andExpect(status().isAccepted())

        val token = register("Org Rep", "rep@test.example")
        val eventId = createEvent(token)

        val csv =
            """
            firstName,lastName,email,phone
            Bad,Address,$bounced,
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(1))
            .andExpect(jsonPath("$.warnings[?(@.reason =~ /.*undeliverable.*/)]").exists())
    }

    private fun createEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Gala","timezone":"Africa/Abidjan"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.id")
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
