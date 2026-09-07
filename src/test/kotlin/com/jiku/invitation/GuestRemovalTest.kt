package com.jiku.invitation

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GuestRemovalTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `removes a guest who has never been invited`() {
        val token = register("Org Remove", "remove@test.example")
        val eventId = createEvent(token)
        val guestId = importGuest(token, eventId, "ada-remove@example.com")

        mockMvc
            .perform(delete("/api/v1/events/$eventId/guests/$guestId").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent())

        mockMvc
            .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `refuses to remove a guest who has already been invited`() {
        val token = register("Org Remove Sent", "remove-sent@test.example")
        val eventId = createEvent(token)
        val guestId = importGuest(token, eventId, "grace-remove@example.com")

        mockMvc
            .perform(post("/api/v1/events/$eventId/invitations/send").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())

        mockMvc
            .perform(delete("/api/v1/events/$eventId/guests/$guestId").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict())

        mockMvc
            .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `excluding a guest skips them on the next invitation send`() {
        val token = register("Org Exclude", "exclude@test.example")
        val eventId = createEvent(token)
        val guestId = importGuest(token, eventId, "excluded@example.com")

        mockMvc
            .perform(
                patch("/api/v1/events/$eventId/guests/$guestId/exclusion")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"excluded":true}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.excludedFromInvitations").value(true))

        mockMvc
            .perform(post("/api/v1/events/$eventId/invitations/send").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(0))
    }

    private fun importGuest(
        token: String,
        eventId: String,
        email: String,
    ): String {
        val csv =
            """
            firstName,lastName,email,phone
            Test,Guest,$email,
            """.trimIndent()
        val body =
            mockMvc
                .perform(
                    multipart("/api/v1/events/$eventId/guests/import")
                        .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                        .header("Authorization", "Bearer $token"),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        check(JsonPath.read<Int>(body, "$.imported") == 1)

        val guestsBody =
            mockMvc
                .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(guestsBody, "$[0].id")
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
                            "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
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
