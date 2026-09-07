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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Ticket transfer (JIKU-64): a confirmed guest hands their place to someone else.
 * Covers the event-level gate, the deadline, the state machine on both sides of
 * the handover, and the guarantee that matters at the door — the old QR code
 * stops validating the moment the new one is issued.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TicketTransferTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `a confirmed guest hands their place over, the old ticket stops working and the new one validates`() {
        val accessToken = register("Org Transfer", "transfer@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken, transferAllowed = true, transferDeadline = null)
        importGuest(accessToken, eventId, "Ada", "Lovelace", "ada-transfer@example.com")
        val guestId = firstGuestId(accessToken, eventId)
        val token = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)

        val confirmed =
            mockMvc
                .perform(post("/api/v1/rsvp/$token/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferAllowed").value(true))
                .andReturn()
                .response
                .contentAsString
        val originalCode: String = JsonPath.read(confirmed, "$.ticketCode")

        mockMvc
            .perform(
                post("/api/v1/rsvp/$token/transfer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"firstName":"Grace","lastName":"Hopper","email":"grace-transfer@example.com"}""",
                    ),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("TRANSFERRED"))
            .andExpect(jsonPath("$.transferredTo").value("Grace Hopper"))
            // The sender no longer holds a ticket, so nothing renders a QR for them.
            .andExpect(jsonPath("$.ticketCode").doesNotExist())

        // The recipient joined the event as a confirmed guest with their own ticket.
        val recipientId = guestIdByEmail(accessToken, eventId, "grace-transfer@example.com")
        val recipientToken = tokenService.issue(UUID.fromString(recipientId), UUID.fromString(eventId), tenantId)
        val recipientView =
            mockMvc
                .perform(get("/api/v1/rsvp/$recipientToken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.ticketCode").isNotEmpty())
                .andReturn()
                .response
                .contentAsString
        val newCode: String = JsonPath.read(recipientView, "$.ticketCode")
        assert(newCode != originalCode) { "the recipient must get a different ticket code" }

        // The headline guest count still reconciles: the superseded row is kept for
        // the audit trail but is not counted as a second attendee.
        mockMvc
            .perform(get("/api/v1/events/$eventId/dashboard").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalGuests").value(1))
            .andExpect(jsonPath("$.confirmed").value(1))

        val validatorToken = issueValidatorLink(accessToken, eventId)

        // The transferred-away code is refused at the door.
        mockMvc
            .perform(
                post("/api/v1/checkin/$validatorToken/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ticketCode":"$originalCode"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CANCELLED"))

        // The reissued code admits the recipient.
        mockMvc
            .perform(
                post("/api/v1/checkin/$validatorToken/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ticketCode":"$newCode"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))

        // A place already used at the entrance cannot be handed on afterwards.
        mockMvc
            .perform(
                post("/api/v1/rsvp/$recipientToken/transfer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"firstName":"Alan","lastName":"Turing","email":"alan-transfer@example.com"}"""),
            ).andExpect(status().isConflict())
    }

    @Test
    fun `transfer is refused when the event forbids it, when the deadline passed, and before confirming`() {
        val accessToken = register("Org NoTransfer", "no-transfer@test.example")
        val tenantId = currentTenantId(accessToken)
        val closedEventId = createEvent(accessToken, transferAllowed = false, transferDeadline = null)
        importGuest(accessToken, closedEventId, "Ada", "Lovelace", "ada-closed@example.com")
        val closedGuestId = firstGuestId(accessToken, closedEventId)
        val closedToken = tokenService.issue(UUID.fromString(closedGuestId), UUID.fromString(closedEventId), tenantId)

        // Not confirmed yet: nothing to hand over.
        mockMvc
            .perform(
                post("/api/v1/rsvp/$closedToken/transfer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"firstName":"Grace","lastName":"Hopper","email":"g-closed@example.com"}"""),
            ).andExpect(status().isConflict())

        mockMvc.perform(post("/api/v1/rsvp/$closedToken/confirm")).andExpect(status().isOk())

        // Confirmed, but the event does not allow transfers — and the view says so,
        // so the UI never offers a control the endpoint would refuse.
        mockMvc
            .perform(get("/api/v1/rsvp/$closedToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transferAllowed").value(false))
        mockMvc
            .perform(
                post("/api/v1/rsvp/$closedToken/transfer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"firstName":"Grace","lastName":"Hopper","email":"g-closed2@example.com"}"""),
            ).andExpect(status().isConflict())

        // A past deadline closes transfers even when the event allows them.
        val expiredEventId =
            createEvent(
                accessToken,
                transferAllowed = true,
                transferDeadline = Instant.now().minus(1, ChronoUnit.DAYS),
            )
        importGuest(accessToken, expiredEventId, "Alan", "Turing", "alan-expired@example.com")
        val expiredGuestId = firstGuestId(accessToken, expiredEventId)
        val expiredToken = tokenService.issue(UUID.fromString(expiredGuestId), UUID.fromString(expiredEventId), tenantId)
        mockMvc.perform(post("/api/v1/rsvp/$expiredToken/confirm")).andExpect(status().isOk())
        mockMvc
            .perform(get("/api/v1/rsvp/$expiredToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transferAllowed").value(false))
        mockMvc
            .perform(
                post("/api/v1/rsvp/$expiredToken/transfer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"firstName":"Grace","lastName":"Hopper","email":"g-expired@example.com"}"""),
            ).andExpect(status().isConflict())
    }

    @Test
    fun `a recipient with no way to be reached is rejected`() {
        val accessToken = register("Org NoContact", "no-contact@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken, transferAllowed = true, transferDeadline = null)
        importGuest(accessToken, eventId, "Ada", "Lovelace", "ada-nocontact@example.com")
        val guestId = firstGuestId(accessToken, eventId)
        val token = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)
        mockMvc.perform(post("/api/v1/rsvp/$token/confirm")).andExpect(status().isOk())

        mockMvc
            .perform(
                post("/api/v1/rsvp/$token/transfer")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"firstName":"Grace","lastName":"Hopper"}"""),
            ).andExpect(status().isBadRequest())
    }

    private fun issueValidatorLink(
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
                .response
                .contentAsString
        // The response carries the shareable URL; the token is its last segment.
        val link: String = JsonPath.read(body, "$.link")
        return link.substringAfterLast('/')
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

    private fun createEvent(
        accessToken: String,
        transferAllowed: Boolean,
        transferDeadline: Instant?,
    ): String {
        val deadline = transferDeadline?.let { ""","transferDeadline":"$it"""" }.orEmpty()
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Gala","timezone":"Africa/Conakry",
                             "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"],
                             "settings":{"transferAllowed":$transferAllowed$deadline}}
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

    private fun importGuest(
        accessToken: String,
        eventId: String,
        firstName: String,
        lastName: String,
        email: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            $firstName,$lastName,$email,
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
                .response
                .contentAsString
        val ids: List<String> = JsonPath.read(body, "$[*].id")
        return ids.first()
    }

    private fun guestIdByEmail(
        accessToken: String,
        eventId: String,
        email: String,
    ): String {
        val body =
            mockMvc
                .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        val guests: List<Map<String, Any?>> = JsonPath.read(body, "$[*]")
        return guests.first { it["email"] == email }["id"] as String
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
