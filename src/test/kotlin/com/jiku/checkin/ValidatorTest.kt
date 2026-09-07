package com.jiku.checkin

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
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
class ValidatorTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `validator link checks guests in attributed to its label, and is rejected once revoked`() {
        val accessToken = register("Org Validator", "validator@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken)
        importGuests(accessToken, eventId)
        val ada = guestIdByFirstName(accessToken, eventId, "Ada")
        val ticketCode = confirmAndGetTicket(ada, eventId, tenantId)

        // The organizer mints a labeled validator link.
        val create =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/validators")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"label":"Main Gate"}"""),
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Main Gate"))
                .andExpect(jsonPath("$.revoked").value(false))
                .andReturn()
                .response
                .contentAsString
        val validatorId: String = JsonPath.read(create, "$.id")
        val link: String = JsonPath.read(create, "$.link")
        val validatorToken = link.substringAfter("/checkin/")

        // Opening the link exposes only event/branding/attendance context.
        mockMvc
            .perform(get("/api/v1/checkin/$validatorToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validatorLabel").value("Main Gate"))
            .andExpect(jsonPath("$.confirmed").value(1))
            .andExpect(jsonPath("$.checkedIn").value(0))

        // A check-in through the link is attributed to the link's label.
        scan(validatorToken, ticketCode)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))
            .andExpect(jsonPath("$.guestName").value("Ada Lovelace"))
            .andExpect(jsonPath("$.checkedInBy").value("Main Gate"))

        // The attendance counter advances.
        mockMvc
            .perform(get("/api/v1/checkin/$validatorToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkedIn").value(1))

        // The organizer revokes the link.
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/validators/$validatorId/revoke")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked").value(true))
            .andExpect(jsonPath("$.revokedAt").isNotEmpty())

        // Further use of the revoked link is rejected on the next attempt.
        scan(validatorToken, ticketCode)
            .andExpect(status().isForbidden())
    }

    @Test
    fun `a validator token cannot reach organizer endpoints`() {
        val accessToken = register("Org Scope", "validator-scope@test.example")
        val eventId = createEvent(accessToken)
        val create =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/validators")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{}"""),
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Entrance"))
                .andReturn()
                .response
                .contentAsString
        val link: String = JsonPath.read(create, "$.link")
        val validatorToken = link.substringAfter("/checkin/")

        // Presented as a Bearer credential to an organizer endpoint, the validator
        // token is not an access token and is denied access to organizer data.
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $validatorToken"))
            .andExpect(status().is4xxClientError())
    }

    private fun scan(
        validatorToken: String,
        code: String,
    ) = mockMvc.perform(
        post("/api/v1/checkin/$validatorToken/scan")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"ticketCode":"$code"}"""),
    )

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
                            "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
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
            Ada,Lovelace,ada-validator@example.com,
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
