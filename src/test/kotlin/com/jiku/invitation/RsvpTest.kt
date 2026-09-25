package com.jiku.invitation

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
class RsvpTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `guest confirms and gets a ticket, capacity is enforced, declining frees the slot`() {
        val accessToken = register("Org RSVP", "rsvp@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEventWithCapacity(accessToken, 1)
        importTwoGuests(accessToken, eventId)
        val (guest1, guest2) = guestIds(accessToken, eventId)

        val token1 = tokenService.issue(UUID.fromString(guest1), UUID.fromString(eventId), tenantId)
        val token2 = tokenService.issue(UUID.fromString(guest2), UUID.fromString(eventId), tenantId)

        mockMvc
            .perform(get("/api/v1/rsvp/$token1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.eventStart").value("2026-12-01T18:00:00Z"))
            .andExpect(jsonPath("$.eventTimezone").value("Africa/Abidjan"))

        mockMvc
            .perform(post("/api/v1/rsvp/$token1/confirm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.ticketCode").isNotEmpty())

        // Capacity is 1: the second guest is rejected while the first is confirmed.
        mockMvc
            .perform(post("/api/v1/rsvp/$token2/confirm"))
            .andExpect(status().isConflict())

        // Declining frees the slot.
        mockMvc
            .perform(post("/api/v1/rsvp/$token1/decline"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DECLINED"))

        // Now the second guest can confirm.
        mockMvc
            .perform(post("/api/v1/rsvp/$token2/confirm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
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

    private fun createEventWithCapacity(
        accessToken: String,
        capacity: Int,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Gala","timezone":"Africa/Abidjan","maxCapacity":$capacity,
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

    private fun importTwoGuests(
        accessToken: String,
        eventId: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Ada,Lovelace,ada-rsvp@example.com,
            Grace,Hopper,grace-rsvp@example.com,
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
    }

    private fun guestIds(
        accessToken: String,
        eventId: String,
    ): Pair<String, String> {
        val body =
            mockMvc
                .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        val ids: List<String> = JsonPath.read(body, "$[*].id")
        return ids[0] to ids[1]
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
