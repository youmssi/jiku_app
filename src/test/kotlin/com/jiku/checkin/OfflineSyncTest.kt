package com.jiku.checkin

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.shared.TenantContext
import com.jiku.ticketing.CheckInOutcome
import com.jiku.ticketing.TicketingModuleApi
import org.junit.jupiter.api.AfterEach
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
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OfflineSyncTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Autowired
    lateinit var ticketing: TicketingModuleApi

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `the earliest offline scan owns the check-in regardless of sync order`() {
        TenantContext.set("tenant-offline")
        val code = ticketing.issueTicket(UUID.randomUUID(), UUID.randomUUID()).ticketCode
        val early = Instant.parse("2026-07-01T18:00:00Z")
        val later = Instant.parse("2026-07-01T18:05:00Z")
        val latest = Instant.parse("2026-07-01T18:10:00Z")

        // Gate B syncs first, claiming the slot at its scan time.
        val first = ticketing.syncCheckInByCode(code, "Gate B", later)
        assertEquals(CheckInOutcome.CHECKED_IN, first.outcome)
        assertEquals("Gate B", first.checkedInBy)

        // Gate A scanned earlier but syncs second — it becomes the authoritative record.
        val earlier = ticketing.syncCheckInByCode(code, "Gate A", early)
        assertEquals(CheckInOutcome.CHECKED_IN, earlier.outcome)
        assertEquals("Gate A", earlier.checkedInBy)
        assertEquals(early, earlier.checkedInAt)

        // A still-later scan loses and is corrected to the earliest record.
        val loser = ticketing.syncCheckInByCode(code, "Gate C", latest)
        assertEquals(CheckInOutcome.ALREADY_CHECKED_IN, loser.outcome)
        assertEquals("Gate A", loser.checkedInBy)
    }

    @Test
    fun `roster lists the guest and the sync endpoint checks them in`() {
        val accessToken = register("Org Offline", "offline@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken)
        importGuest(accessToken, eventId)
        val ada = guestIdByFirstName(accessToken, eventId, "Ada")
        val ticketCode = confirmAndGetTicket(ada, eventId, tenantId)
        val validatorToken = mintValidator(accessToken, eventId)

        // The roster pre-sync exposes the guest with their ticket code and status.
        mockMvc
            .perform(get("/api/v1/checkin/$validatorToken/roster"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.name=='Ada Lovelace')].ticketCode").value(ticketCode))
            .andExpect(jsonPath("$[?(@.name=='Ada Lovelace')].ticketStatus").value("ISSUED"))

        // A queued offline check-in is applied through the sync endpoint.
        mockMvc
            .perform(
                post("/api/v1/checkin/$validatorToken/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"items":[{"ticketCode":"$ticketCode","scannedAt":"2026-07-01T18:00:00Z"}]}""",
                    ),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].outcome").value("CHECKED_IN"))
            .andExpect(jsonPath("$[0].guestName").value("Ada Lovelace"))

        mockMvc
            .perform(get("/api/v1/checkin/$validatorToken/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checkedIn").value(1))
    }

    private fun mintValidator(
        accessToken: String,
        eventId: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/validators")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"label":"Gate"}"""),
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
                        .content("""{"name":"Gala","timezone":"Africa/Abidjan"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.id")
    }

    private fun importGuest(
        accessToken: String,
        eventId: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Ada,Lovelace,ada-offline@example.com,
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
