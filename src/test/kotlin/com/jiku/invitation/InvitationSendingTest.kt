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
class InvitationSendingTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `queues and delivers invitations and tracks per-guest status`() {
        val token = register("Org Invite", "invite@test.example")
        val eventId = createEvent(token)
        importGuests(token, eventId)

        mockMvc
            .perform(post("/api/v1/events/$eventId/invitations/send").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(2))

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
                val statuses: List<String> = JsonPath.read(body, "$[*].status")
                assertEquals(2, statuses.size)
                assertTrue(statuses.all { it == "SENT" }, "all invitations should be SENT, was $statuses")
            }
    }

    private fun importGuests(
        token: String,
        eventId: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Ada,Lovelace,ada-invite@example.com,
            Grace,Hopper,grace-invite@example.com,
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(2))
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
