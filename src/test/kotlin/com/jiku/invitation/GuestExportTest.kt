package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.support.TestDates.EVENT_YEAR
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GuestExportTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `export streams a csv of guests with their statuses`() {
        val accessToken = register("Org Export", "export@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken)
        importGuests(accessToken, eventId)
        val ada = guestIdByFirstName(accessToken, eventId, "Ada")
        val ticketCode = confirmAndGetTicket(ada, eventId, tenantId)

        // Check Ada in (organizer path) so the export carries check-in state.
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/checkin/scan")
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ticketCode":"$ticketCode"}"""),
            ).andExpect(status().isOk())

        val csv =
            mockMvc
                .perform(get("/api/v1/events/$eventId/guests/export").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andReturn()
                .response
                .contentAsString

        assertTrue(csv.lineSequence().first().startsWith("First name,Last name,Email"))
        assertTrue(csv.contains("Ada,Lovelace,ada-export@example.com"))
        // Ada is confirmed and checked in by the organizer; Grace is still pending.
        assertTrue(csv.contains("CONFIRMED"))
        assertTrue(csv.contains("CHECKED_IN"))
        assertTrue(csv.contains("Grace,Hopper"))
    }

    private fun confirmAndGetTicket(
        guestId: String,
        eventId: String,
        tenantId: String,
    ): String {
        val token = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)
        val body =
            mockMvc
                .perform(post("/api/v1/rsvp/$token/confirm"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.ticketCode")
    }

    private fun currentTenantId(accessToken: String): String {
        val body =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.tenantId")
    }

    private fun createEvent(accessToken: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Gala","timezone":"Africa/Abidjan",
                            "startDateTime":"${EVENT_YEAR}-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
        return eventId
    }

    private fun importGuests(
        accessToken: String,
        eventId: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Ada,Lovelace,ada-export@example.com,
            Grace,Hopper,grace-export@example.com,
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
    }

    private fun guestIdByFirstName(
        accessToken: String,
        eventId: String,
        firstName: String,
    ): String {
        val body =
            mockMvc
                .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        val ids: List<String> = JsonPath.read(body, "$[?(@.firstName=='$firstName')].id")
        return ids.first()
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
