package com.jiku.checkin

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DashboardTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `dashboard aggregates rsvp, attendance and per-entrance check-in counts`() {
        val accessToken = register("Org Dash", "dash@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken)
        importGuests(accessToken, eventId)
        val ada = guestIdByFirstName(accessToken, eventId, "Ada")
        val grace = guestIdByFirstName(accessToken, eventId, "Grace")

        // Ada confirms and is checked in at the Main Gate; Grace declines.
        val adaTicket = confirmAndGetTicket(ada, eventId, tenantId)
        decline(grace, eventId, tenantId)
        val validatorToken = mintValidator(accessToken, eventId, "Main Gate")
        mockMvc
            .perform(
                post("/api/v1/checkin/$validatorToken/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ticketCode":"$adaTicket"}"""),
            ).andExpect(status().isOk())

        mockMvc
            .perform(get("/api/v1/events/$eventId/dashboard").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalGuests").value(2))
            .andExpect(jsonPath("$.confirmed").value(1))
            .andExpect(jsonPath("$.declined").value(1))
            .andExpect(jsonPath("$.pending").value(0))
            .andExpect(jsonPath("$.invited").value(0))
            .andExpect(jsonPath("$.checkedIn").value(1))
            .andExpect(jsonPath("$.entrances[?(@.label=='Main Gate')].checkedIn").value(1))
    }

    private fun mintValidator(
        accessToken: String,
        eventId: String,
        label: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/validators")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"label":"$label"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        val link: String = JsonPath.read(body, "$.link")
        return link.substringAfter("/checkin/")
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

    private fun decline(
        guestId: String,
        eventId: String,
        tenantId: String,
    ) {
        val token = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)
        mockMvc
            .perform(post("/api/v1/rsvp/$token/decline"))
            .andExpect(status().isOk())
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
            Ada,Lovelace,ada-dash@example.com,
            Grace,Hopper,grace-dash@example.com,
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
