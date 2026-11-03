package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.support.TestDates.EVENT_YEAR
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JIKU-281: the organizer sends each guest's personal link from their own WhatsApp. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class SendFromOwnWhatsAppTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `preparing gives a personal link to each guest with a WhatsApp number and sends nothing`(output: CapturedOutput) {
        val token = register()
        val eventId = createEvent(token)
        importGuests(token, eventId, "Ada,Lovelace,,+2250700000201", "Grace,Hopper,grace@test.example,")

        val prepared = prepare(token, eventId)

        assertEquals(listOf("Ada"), JsonPath.read<List<String>>(prepared, "$[*].firstName"))
        assertEquals("+2250700000201", JsonPath.read(prepared, "$[0].phone"))
        assertTrue(JsonPath.read<String>(prepared, "$[0].link").matches(Regex(".+/v/[A-HJKMNP-Z2-9]{10}$")))
        assertNull(JsonPath.read<String?>(prepared, "$[0].sentAt"))
        Thread.sleep(1500)
        assertEquals(listOf("PREPARED"), JsonPath.read<List<String>>(invitations(token, eventId), "$[*].status"))
        assertTrue(!output.out.contains("to=+2250700000201"), "Jikū delivered the invitation itself")
    }

    @Test
    fun `opening the chat marks the invitation sent once, and preparing again keeps the same link`() {
        val token = register()
        val eventId = createEvent(token)
        importGuests(token, eventId, "Ada,Lovelace,,+2250700000202")
        val prepared = prepare(token, eventId)
        val guestId = JsonPath.read<String>(prepared, "$[0].guestId")
        val link = JsonPath.read<String>(prepared, "$[0].link")

        markSent(token, eventId, guestId).andExpect(status().isOk()).andExpect(jsonPath("$.sentByOrganizer").value(true))
        val firstSentAt = JsonPath.read<String>(invitations(token, eventId), "$[0].sentAt")
        markSent(token, eventId, guestId).andExpect(status().isOk())

        val body = invitations(token, eventId)
        assertEquals(listOf("SENT"), JsonPath.read<List<String>>(body, "$[*].status"))
        assertEquals(firstSentAt, JsonPath.read(body, "$[0].sentAt"))
        val again = prepare(token, eventId)
        assertEquals(link, JsonPath.read(again, "$[0].link"))
        assertNotNull(JsonPath.read<String?>(again, "$[0].sentAt"))
    }

    @Test
    fun `a guest Jikū already invites is listed without a link`() {
        val token = register()
        val eventId = createEvent(token)
        importGuests(token, eventId, "Ada,Lovelace,,+2250700000203")
        mockMvc
            .perform(post("/api/v1/events/$eventId/invitations/send").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(1))

        val prepared = prepare(token, eventId)

        assertNull(JsonPath.read<String?>(prepared, "$[0].link"))
    }

    @Test
    fun `a chat cannot be marked before its link is prepared, nor on a draft event`() {
        val token = register()
        val eventId = createEvent(token)
        importGuests(token, eventId, "Ada,Lovelace,,+2250700000204")
        val guestId = JsonPath.read<String>(guests(token, eventId), "$[0].id")
        markSent(token, eventId, guestId).andExpect(status().isConflict())

        val draft = createEvent(token, publish = false)
        mockMvc
            .perform(post("/api/v1/events/$draft/invitations/own-whatsapp").header("Authorization", "Bearer $token"))
            .andExpect(status().isConflict())
    }

    @Test
    fun `a guest invited from the organizer's WhatsApp gets the ticket by email, not from Jikū's WhatsApp`(output: CapturedOutput) {
        val token = register()
        val eventId = createEvent(token)
        importGuests(token, eventId, "Ada,Lovelace,ada-own@test.example,+2250700000205")
        val guestId = JsonPath.read<String>(prepare(token, eventId), "$[0].guestId")
        markSent(token, eventId, guestId).andExpect(status().isOk())

        confirm(token, eventId, guestId)

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250)).untilAsserted {
            assertTrue(output.out.contains("subject=\"Votre billet : Gala\""), "the ticket did not go by email")
        }
        assertTrue(!output.out.contains("to=+2250700000205"), "the ticket went through Jikū's WhatsApp")
    }

    private fun prepare(
        token: String,
        eventId: String,
    ): String =
        mockMvc
            .perform(post("/api/v1/events/$eventId/invitations/own-whatsapp").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andReturn()
            .response
            .contentAsString

    private fun markSent(
        token: String,
        eventId: String,
        guestId: String,
    ) = mockMvc.perform(
        post("/api/v1/events/$eventId/invitations/own-whatsapp/$guestId/sent").header("Authorization", "Bearer $token"),
    )

    private fun confirm(
        token: String,
        eventId: String,
        guestId: String,
    ) {
        val tenantId =
            JsonPath.read<String>(
                mockMvc
                    .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                    .andReturn()
                    .response
                    .contentAsString,
                "$.tenantId",
            )
        val rsvp = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)
        mockMvc.perform(post("/api/v1/rsvp/$rsvp/confirm")).andExpect(status().isOk())
    }

    private fun invitations(
        token: String,
        eventId: String,
    ): String =
        mockMvc
            .perform(get("/api/v1/events/$eventId/invitations").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andReturn()
            .response
            .contentAsString

    private fun guests(
        token: String,
        eventId: String,
    ): String =
        mockMvc
            .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
            .andReturn()
            .response
            .contentAsString

    private fun importGuests(
        token: String,
        eventId: String,
        vararg rows: String,
    ) {
        val lines = (listOf("firstName,lastName,email,phone") + rows).joinToString("\n")
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .param("consentAttested", "true")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", lines.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(rows.size))
    }

    private fun createEvent(
        token: String,
        publish: Boolean = true,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Gala","timezone":"Africa/Conakry","startDateTime":"$EVENT_YEAR-12-01T18:00:00Z",
                            "invitationChannels":["EMAIL","WHATSAPP"]}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        if (publish) {
            mockMvc
                .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk())
        }
        return eventId
    }

    private fun register(): String {
        val email = "own-whatsapp-${UUID.randomUUID()}@test.example"
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Org Own WhatsApp","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
