package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.GuestInviteCodes
import com.jiku.invitation.internal.GuestRepository
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates.EVENT_YEAR
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Short personal links (JIKU-248): a guest's invitation reads `/v/K7M2QX9PAB`
 * instead of a 400-character signed token, the code answers the invitation
 * exactly as the token did, and links already sent with a token keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class InviteCodeTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var inviteCodes: GuestInviteCodes

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Autowired
    lateinit var guests: GuestRepository

    @Test
    fun `a sent ticket carries the short code, which opens the invitation`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId = publishedEvent(api, token, "Baptême Camara", directTicket = true)
        val phone = "+22462" + Random.nextInt(1_000_000, 9_999_999)
        importGuest(token, eventId, phone)

        api.post(token, "/api/v1/events/$eventId/invitations/send?channels=WHATSAPP", "{}").andExpect(status().isOk())
        val code =
            await().atMost(10, TimeUnit.SECONDS).until(
                { QR_BY_CODE.find(output.out.substringAfter("to=$phone", ""))?.groupValues?.get(1) },
                { it != null },
            )!!

        mockMvc
            .perform(get("/api/v1/rsvp/$code"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventName").value("Baptême Camara"))
        // Someone retyping the link from a screenshot may not keep the capitals.
        mockMvc.perform(get("/api/v1/rsvp/${code.lowercase()}")).andExpect(status().isOk())
        mockMvc.perform(get("/api/v1/rsvp/$code/qr.png")).andExpect(status().isOk())
    }

    @Test
    fun `a guest answers an invitation with the short code`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId = publishedEvent(api, token, "Gala Sow")
        importGuest(token, eventId, "+22462" + Random.nextInt(1_000_000, 9_999_999))
        val code = codeOfFirstGuest(api, token, eventId)

        mockMvc
            .perform(post("/api/v1/rsvp/$code/confirm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.ticketCode").exists())
    }

    @Test
    fun `a guest keeps one code, and a link sent with a signed token still works`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val tenantId = api.tenantId(token)
        val eventId = publishedEvent(api, token, "Gala Barry")
        importGuest(token, eventId, "+22462" + Random.nextInt(1_000_000, 9_999_999))
        val guestId = firstGuestId(api, token, eventId)
        val first = codeOfFirstGuest(api, token, eventId)
        val second = codeOfFirstGuest(api, token, eventId)
        assertEquals(first, second)
        assertTrue(Regex("[A-HJKMNP-Z2-9]{10}").matches(first)) { "unexpected code shape: $first" }

        val signed = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)
        mockMvc.perform(get("/api/v1/rsvp/$signed")).andExpect(status().isOk())
        mockMvc.perform(get("/api/v1/rsvp/$first")).andExpect(status().isOk())
    }

    @Test
    fun `an unknown code is not found`() {
        mockMvc.perform(get("/api/v1/rsvp/ZZZZZZZZZZ")).andExpect(status().isNotFound())
        mockMvc.perform(post("/api/v1/rsvp/ZZZZZZZZZZ/confirm")).andExpect(status().isNotFound())
    }

    private fun firstGuestId(
        api: OrganizerApi,
        token: String,
        eventId: String,
    ): String =
        JsonPath
            .read<List<String>>(
                api
                    .get(token, "/api/v1/events/$eventId/guests")
                    .andReturn()
                    .response.contentAsString,
                "$[*].id",
            ).first()

    /** Draws (or reads back) the first guest's code, as sending does, under the organization's tenant. */
    private fun codeOfFirstGuest(
        api: OrganizerApi,
        token: String,
        eventId: String,
    ): String {
        val guestId = UUID.fromString(firstGuestId(api, token, eventId))
        TenantContext.set(api.tenantId(token))
        try {
            return inviteCodes.codeFor(guests.findById(guestId).orElseThrow())
        } finally {
            TenantContext.clear()
        }
    }

    private fun publishedEvent(
        api: OrganizerApi,
        token: String,
        name: String,
        directTicket: Boolean = false,
    ): String {
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """{"name":"$name","timezone":"Africa/Conakry","startDateTime":"$EVENT_YEAR-12-05T17:00:00Z","invitationChannels":["WHATSAPP"]${if (directTicket) ""","settings":{"deliveryMode":"DIRECT_TICKET"}""" else ""}}""",
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        return eventId
    }

    private fun importGuest(
        token: String,
        eventId: String,
        phone: String,
    ) {
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .param("consentAttested", "true")
                    .file(
                        MockMultipartFile(
                            "file",
                            "guests.csv",
                            "text/csv",
                            "firstName,lastName,email,phone\nMariama,Camara,,$phone".toByteArray(),
                        ),
                    ).header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private companion object {
        val QR_BY_CODE = Regex("/rsvp/([A-HJKMNP-Z2-9]{10})/qr\\.png")
    }
}
