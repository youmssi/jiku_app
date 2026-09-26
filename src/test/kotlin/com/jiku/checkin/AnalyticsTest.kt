package com.jiku.checkin

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.support.TestDates.EVENT_YEAR
import org.awaitility.Awaitility.await
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
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AnalyticsTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `analytics reports channel breakdown, guest growth and the check-in timeline`() {
        val accessToken = register("Org Analytics", "analytics@test.example")
        val tenantId = currentTenantId(accessToken)
        val eventId = createEvent(accessToken)
        importGuests(accessToken, eventId)

        mockMvc
            .perform(
                post("/api/v1/events/$eventId/invitations/send")
                    .param("channels", "EMAIL")
                    .header("Authorization", "Bearer $accessToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(2))

        await()
            .atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(250))
            .untilAsserted {
                val body =
                    mockMvc
                        .perform(get("/api/v1/events/$eventId/invitations").header("Authorization", "Bearer $accessToken"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .response
                        .contentAsString
                val statuses: List<String> = JsonPath.read(body, "$[*].status")
                assert(statuses.size == 2 && statuses.all { it == "SENT" }) { "status was $statuses" }
            }

        val ada = guestIdByFirstName(accessToken, eventId, "Ada")
        val adaTicket = confirmAndGetTicket(ada, eventId, tenantId)
        val validatorToken = mintValidator(accessToken, eventId, "Main Gate")
        mockMvc
            .perform(
                post("/api/v1/checkin/$validatorToken/scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"ticketCode":"$adaTicket"}"""),
            ).andExpect(status().isOk())

        val today = LocalDate.now(ZoneOffset.UTC).toString()
        mockMvc
            .perform(get("/api/v1/events/$eventId/analytics").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.channelBreakdown[?(@.channel=='EMAIL')].sent").value(2))
            .andExpect(jsonPath("$.channelBreakdown[?(@.channel=='EMAIL')].failed").value(0))
            .andExpect(jsonPath("$.channelBreakdown[?(@.channel=='WHATSAPP')].sent").value(0))
            .andExpect(jsonPath("$.guestGrowth[?(@.date=='$today')].count").value(2))
            .andExpect(jsonPath("$.checkInTimeline.length()").value(1))
            .andExpect(jsonPath("$.checkInTimeline[0].count").value(1))
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
            Ada,Lovelace,ada-analytics@example.com,
            Grace,Hopper,grace-analytics@example.com,
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
