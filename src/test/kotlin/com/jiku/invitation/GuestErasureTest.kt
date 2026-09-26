package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.GuestRepository
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.shared.TenantContext
import com.jiku.support.TestDates.EVENT_YEAR
import org.assertj.core.api.Assertions.assertThat
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

/**
 * JIKU-36: after a guest erases their data, no personal identifier is recoverable,
 * while the event's aggregate attendance counts are unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GuestErasureTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Autowired
    lateinit var guests: GuestRepository

    @Test
    fun `erasing a guest anonymizes personal data while aggregate counts stay accurate`() {
        val token = register()
        val tenantId = tenantId(token)
        val eventId = createEvent(token)
        importGuest(token, eventId)
        val guestId = firstGuestId(token, eventId)

        val rsvpToken = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)

        // Guest confirms — contributes to the confirmed aggregate and holds a ticket.
        mockMvc
            .perform(post("/api/v1/rsvp/$rsvpToken/confirm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CONFIRMED"))

        val confirmedBefore = confirmedCount(token, eventId)
        assertThat(confirmedBefore).isEqualTo(1)

        // Erase.
        mockMvc
            .perform(post("/api/v1/rsvp/$rsvpToken/erase"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.erased").value(true))
            .andExpect(jsonPath("$.guestName").value("Deleted Guest"))

        // No personal identifier is recoverable from the database.
        TenantContext.set(tenantId)
        try {
            val guest = guests.findById(UUID.fromString(guestId)).orElseThrow()
            assertThat(guest.personalDataErased).isTrue()
            assertThat(guest.erasedAt).isNotNull()
            assertThat(guest.email).isNull()
            assertThat(guest.phoneNumber).isNull()
            assertThat(guest.firstName).isEqualTo("Deleted")
            assertThat(guest.lastName).isEqualTo("Guest")
            // The RSVP status (aggregate contribution) and the guest row are preserved.
            assertThat(guest.rsvpStatus.name).isEqualTo("CONFIRMED")
        } finally {
            TenantContext.clear()
        }

        // The event's confirmed aggregate is unchanged after erasure.
        assertThat(confirmedCount(token, eventId)).isEqualTo(confirmedBefore)
    }

    private fun confirmedCount(
        token: String,
        eventId: String,
    ): Int =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/events/$eventId/dashboard").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.confirmed",
        )

    private fun firstGuestId(
        token: String,
        eventId: String,
    ): String {
        val ids: List<String> =
            JsonPath.read(
                mockMvc
                    .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
                    .andReturn()
                    .response.contentAsString,
                "$[*].id",
            )
        return ids.first()
    }

    private fun importGuest(
        token: String,
        eventId: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Jane,Doe,jane.doe@example.com,+2250700000000
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private fun tenantId(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString,
            "$.tenantId",
        )

    private fun createEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Erasure Event","timezone":"Africa/Abidjan","maxCapacity":10,
                            "startDateTime":"${EVENT_YEAR}-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        return eventId
    }

    private fun register(): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Erase Org","email":"erase-${UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
}
