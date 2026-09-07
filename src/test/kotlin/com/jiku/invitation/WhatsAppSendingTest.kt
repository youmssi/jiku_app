package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.awaitility.Awaitility.await
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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WhatsAppSendingTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `sends WhatsApp invitations to guests with a phone number`() {
        val token = register("Org WhatsApp", "whatsapp@test.example")
        val eventId = createEvent(token)

        val csv =
            """
            firstName,lastName,email,phone
            Ada,Lovelace,,+2250700000001
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(1))

        mockMvc
            .perform(
                post("/api/v1/events/$eventId/invitations/send")
                    .param("channels", "WHATSAPP")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(1))

        await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted {
                val body =
                    mockMvc
                        .perform(get("/api/v1/events/$eventId/invitations").header("Authorization", "Bearer $token"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .response
                        .contentAsString
                val channels: List<String> = JsonPath.read(body, "$[*].channel")
                val statuses: List<String> = JsonPath.read(body, "$[*].status")
                assertEquals(1, statuses.size)
                assertEquals("WHATSAPP", channels.first())
                assertTrue(statuses.all { it == "SENT" }, "status was $statuses")
            }
    }

    private fun createEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Gala","timezone":"Africa/Abidjan",
                            "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL","WHATSAPP"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        return eventId
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
