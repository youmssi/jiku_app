package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.TestDates.EVENT_YEAR
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

/** JIKU-293: the organizer picks the language an event's guests read, and their links open in it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EventGuestLanguageTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `an event's guests read the organization's language until the organizer picks another`() {
        val token = register(country = "GN")
        val eventId = createEvent(token)

        mockMvc
            .perform(get("/api/v1/events/$eventId/guest-language").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.language").value("fr"))
            .andExpect(jsonPath("$.chosen").value(false))
        choose(token, eventId, "\"ar\"")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.language").value("ar"))
            .andExpect(jsonPath("$.chosen").value(true))
        choose(token, eventId, "null")
            .andExpect(jsonPath("$.language").value("fr"))
            .andExpect(jsonPath("$.chosen").value(false))
        choose(token, eventId, "\"de\"").andExpect(status().isBadRequest())
    }

    @Test
    fun `a guest of an event in Arabic gets a personal link that opens in Arabic`() {
        val token = register(country = "GN")
        val eventId = createEvent(token)
        choose(token, eventId, "\"ar\"").andExpect(status().isOk())
        importGuest(token, eventId, "Layla,Diallo,,+2250700000301")

        val prepared =
            mockMvc
                .perform(post("/api/v1/events/$eventId/invitations/own-whatsapp").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        val link = JsonPath.read<String>(prepared, "$[0].link")
        assertTrue(link.matches(Regex(".+/ar/v/[A-HJKMNP-Z2-9]{10}$")), link)
    }

    @Test
    fun `another organization cannot read or change an event's guest language`() {
        val owner = register(country = "GN")
        val eventId = createEvent(owner)
        val stranger = register(country = "GN")

        mockMvc
            .perform(get("/api/v1/events/$eventId/guest-language").header("Authorization", "Bearer $stranger"))
            .andExpect(status().isNotFound())
        choose(stranger, eventId, "\"ar\"").andExpect(status().isNotFound())
    }

    private fun choose(
        token: String,
        eventId: String,
        language: String,
    ) = mockMvc.perform(
        put("/api/v1/events/$eventId/guest-language")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"language":$language}"""),
    )

    private fun importGuest(
        token: String,
        eventId: String,
        row: String,
    ) {
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .param("consentAttested", "true")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", "firstName,lastName,email,phone\n$row".toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private fun createEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Soirée","timezone":"Africa/Conakry","startDateTime":"$EVENT_YEAR-12-01T18:00:00Z",
                            "invitationChannels":["EMAIL","WHATSAPP"]}""",
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

    private fun register(country: String): String {
        val email = "guest-language-${UUID.randomUUID()}@test.example"
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Org Langue","email":"$email","password":"supersecret","country":"$country"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
