package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
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
import java.util.concurrent.TimeUnit

/**
 * JIKU-14B: cancelling a published event must cascade consistently — tickets
 * invalidated atomically, RSVPs closed, validators told explicitly, guests
 * notified on the channels that originally reached them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class EventCancellationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `cancelling a published event invalidates tickets, closes rsvp and notifies guests`(output: CapturedOutput) {
        val accessToken = register("Org Cancel", "cancel@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken, "Summit")
        importGuests(accessToken, eventId)
        val guestId = firstGuestId(accessToken, eventId)

        publish(accessToken, eventId)
        sendInvitations(accessToken, eventId)

        // Guest confirms and holds a valid ticket.
        val rsvpToken = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)
        val ticketCode =
            JsonPath.read<String>(
                mockMvc
                    .perform(post("/api/v1/rsvp/$rsvpToken/confirm"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ticketCode").isNotEmpty())
                    .andReturn()
                    .response.contentAsString,
                "$.ticketCode",
            )
        val validatorToken = createValidatorToken(accessToken, eventId)

        // Cancel: explicit action, event transitions to CANCELLED.
        mockMvc
            .perform(post("/api/v1/events/$eventId/cancel").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        // Cancelled is terminal: no re-publish, no second cancel, no edits.
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isConflict())
        mockMvc
            .perform(post("/api/v1/events/$eventId/cancel").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isConflict())
        mockMvc
            .perform(
                put("/api/v1/events/$eventId")
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Renamed","timezone":"Africa/Abidjan"}"""),
            ).andExpect(status().isConflict())

        // The RSVP flow is closed and says why; the view carries the event status.
        mockMvc
            .perform(post("/api/v1/rsvp/$rsvpToken/confirm"))
            .andExpect(status().isGone())
        mockMvc
            .perform(get("/api/v1/rsvp/$rsvpToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventStatus").value("CANCELLED"))

        // A validator scanning the ticket gets the explicit cancellation outcome,
        // never a generic failure or a false success.
        mockMvc
            .perform(
                post("/api/v1/checkin/$validatorToken/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ticketCode":"$ticketCode"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("EVENT_CANCELLED"))
        mockMvc
            .perform(get("/api/v1/checkin/$validatorToken/search?q=Ada"))
            .andExpect(status().isGone())
        mockMvc
            .perform(get("/api/v1/checkin/$validatorToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventStatus").value("CANCELLED"))

        // Both invited guests are notified on their original channel (email here),
        // regardless of RSVP status — asynchronously, after the commit.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            val sends = output.out
            assert(sends.contains("subject=\"Summit est annulé\"")) { "no cancellation email was sent" }
            assert(sends.contains("to=ada-cancel@example.com")) { "confirmed guest was not notified" }
            assert(sends.contains("to=grace-cancel@example.com")) { "pending guest was not notified" }
        }
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
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun currentTenantId(accessToken: String): String {
        val body =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.tenantId")
    }

    private fun createEvent(
        accessToken: String,
        name: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"$name","timezone":"Africa/Abidjan",
                             "startDateTime":"2026-12-01T18:00:00Z",
                             "invitationChannels":["EMAIL"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.id")
    }

    private fun publish(
        accessToken: String,
        eventId: String,
    ) {
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
    }

    private fun importGuests(
        accessToken: String,
        eventId: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Ada,Lovelace,ada-cancel@example.com,
            Grace,Hopper,grace-cancel@example.com,
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
    }

    private fun firstGuestId(
        accessToken: String,
        eventId: String,
    ): String {
        val body =
            mockMvc
                .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        val ids: List<String> = JsonPath.read(body, "$[*].id")
        return ids.first()
    }

    private fun sendInvitations(
        accessToken: String,
        eventId: String,
    ) {
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/invitations/send?channels=EMAIL")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
        // Delivery is asynchronous; the cascade only notifies guests whose
        // invitation actually went out, so wait for both to be SENT.
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            mockMvc
                .perform(get("/api/v1/events/$eventId/invitations").header("Authorization", "Bearer $accessToken"))
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[1].status").value("SENT"))
        }
    }

    private fun createValidatorToken(
        accessToken: String,
        eventId: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/validators")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"label":"Main gate"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val link: String = JsonPath.read(body, "$.link")
        return link.substringAfterLast("/checkin/")
    }
}
