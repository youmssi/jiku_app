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
class CheckInTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `scan checks in once, rejects a second scan, supports search, manual check-in and counters`() {
        val accessToken = register("Org Checkin", "checkin@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken)
        importGuests(accessToken, eventId)
        val ada = guestIdByFirstName(accessToken, eventId, "Ada")
        val grace = guestIdByFirstName(accessToken, eventId, "Grace")

        // Both guests confirm, each receiving a ticket.
        val code1 = confirmAndGetTicket(ada, eventId, tenantId)
        confirmAndGetTicket(grace, eventId, tenantId)

        // First scan succeeds with the guest's details.
        scan(accessToken, eventId, code1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))
            .andExpect(jsonPath("$.guestName").value("Ada Lovelace"))
            .andExpect(jsonPath("$.checkedInBy").value("Organizer"))
            .andExpect(jsonPath("$.checkedInAt").isNotEmpty())

        // Second scan of the same ticket is rejected with the prior check-in details.
        scan(accessToken, eventId, code1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("ALREADY_CHECKED_IN"))
            .andExpect(jsonPath("$.checkedInBy").value("Organizer"))
            .andExpect(jsonPath("$.checkedInAt").isNotEmpty())

        // An unknown code is reported as not found, not a generic error.
        scan(accessToken, eventId, "not-a-real-code")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))

        // A code scanned against the wrong event is rejected (event scoping).
        val otherEvent = createEvent(accessToken)
        scan(accessToken, otherEvent, code1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))

        // Search finds the matching guest, scoped to the event, with ticket state.
        mockMvc
            .perform(
                get("/api/v1/events/$eventId/checkin/search")
                    .param("q", "ada")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Ada Lovelace"))
            .andExpect(jsonPath("$[0].ticketStatus").value("CHECKED_IN"))

        // Manual (search-based) check-in transitions the second guest the same way.
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/checkin/manual")
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"guestId":"$grace"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))
            .andExpect(jsonPath("$.guestName").value("Grace Hopper"))

        // Counters reflect both check-ins against both confirmed guests.
        mockMvc
            .perform(get("/api/v1/events/$eventId/checkin/stats").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkedIn").value(2))
            .andExpect(jsonPath("$.confirmed").value(2))
    }

    @Test
    fun `a ticket cannot be checked in from another tenant`() {
        val ownerToken = register("Owner Org", "owner-checkin@test.example")
        val ownerTenant = currentTenantId(ownerToken)
        val eventId = createEvent(ownerToken)
        importGuests(ownerToken, eventId)
        val ada = guestIdByFirstName(ownerToken, eventId, "Ada")
        val code1 = confirmAndGetTicket(ada, eventId, ownerTenant)

        // A different tenant's organizer cannot resolve or check in that ticket.
        val intruderToken = register("Intruder Org", "intruder-checkin@test.example")
        val intruderEvent = createEvent(intruderToken)
        scan(intruderToken, intruderEvent, code1)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))
    }

    private fun scan(
        accessToken: String,
        eventId: String,
        code: String,
    ) = mockMvc.perform(
        post("/api/v1/events/$eventId/checkin/scan")
            .header("Authorization", "Bearer $accessToken")
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
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
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
            Ada,Lovelace,ada-checkin@example.com,
            Grace,Hopper,grace-checkin@example.com,
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
