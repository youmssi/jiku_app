package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.InvitationChannel
import com.jiku.invitation.internal.NotificationQueueSweepJob
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
import java.util.UUID
import kotlin.test.assertEquals

/** JIKU-273 (ADR 110, decision 4): a guest gets one invitation, on one channel. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OneChannelPerGuestTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var sweep: NotificationQueueSweepJob

    @Test
    fun `the channel order puts the primary first and adds the other only with fallback`() {
        val both = setOf(InvitationChannel.EMAIL, InvitationChannel.WHATSAPP)
        assertEquals(listOf(InvitationChannel.WHATSAPP, InvitationChannel.EMAIL), InvitationChannel.channelOrder(both, null, true))
        assertEquals(
            listOf(InvitationChannel.EMAIL, InvitationChannel.WHATSAPP),
            InvitationChannel.channelOrder(both, InvitationChannel.EMAIL, true),
        )
        assertEquals(listOf(InvitationChannel.WHATSAPP), InvitationChannel.channelOrder(both, null, false))
        assertEquals(
            listOf(InvitationChannel.EMAIL),
            InvitationChannel.channelOrder(setOf(InvitationChannel.EMAIL), InvitationChannel.WHATSAPP, true),
        )
    }

    @Test
    fun `a guest with an email and a phone gets one invitation, on the primary channel`() {
        val token = register()
        val whatsappFirst = createEvent(token, primary = null)
        importGuests(token, whatsappFirst, "Ada,Lovelace,ada@test.example,+2250700000101")
        send(token, whatsappFirst, "EMAIL", "WHATSAPP").andExpect(jsonPath("$.queued").value(1))
        assertEquals(listOf("WHATSAPP"), awaitChannels(token, whatsappFirst, 1))

        val emailFirst = createEvent(token, primary = "EMAIL")
        importGuests(token, emailFirst, "Grace,Hopper,grace@test.example,+2250700000102")
        send(token, emailFirst).andExpect(jsonPath("$.queued").value(1))
        assertEquals(listOf("EMAIL"), awaitChannels(token, emailFirst, 1))
    }

    @Test
    fun `without fallback a guest the primary cannot reach is left out`() {
        val token = register()
        val withFallback = createEvent(token, primary = "WHATSAPP")
        importGuests(token, withFallback, "Ada,Lovelace,ada@test.example,")
        send(token, withFallback).andExpect(jsonPath("$.queued").value(1))
        assertEquals(listOf("EMAIL"), awaitChannels(token, withFallback, 1))

        val withoutFallback = createEvent(token, primary = "WHATSAPP", fallback = false)
        importGuests(token, withoutFallback, "Grace,Hopper,grace@test.example,")
        send(token, withoutFallback).andExpect(jsonPath("$.queued").value(0))
    }

    @Test
    fun `the import's channel column decides for that guest`() {
        val token = register()
        val eventId = createEvent(token, primary = "WHATSAPP")
        importGuests(token, eventId, "Ada,Lovelace,ada@test.example,+2250700000103,email", header = "firstName,lastName,email,phone,canal")
        mockMvc
            .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$[0].preferredChannel").value("EMAIL"))
        send(token, eventId).andExpect(jsonPath("$.queued").value(1))
        assertEquals(listOf("EMAIL"), awaitChannels(token, eventId, 1))
    }

    @Test
    fun `a reminder on one channel only uses that channel`() {
        val token = register()
        val eventId = createEvent(token, primary = "WHATSAPP")
        importGuests(token, eventId, "Ada,Lovelace,ada@test.example,+2250700000104")
        send(token, eventId).andExpect(jsonPath("$.queued").value(1))
        awaitChannels(token, eventId, 1)

        send(token, eventId).andExpect(jsonPath("$.queued").value(0))
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/invitations/send")
                    .param("channels", "EMAIL")
                    .param("onlyUnsent", "false")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.queued").value(1))
        assertEquals(listOf("EMAIL", "WHATSAPP"), awaitChannels(token, eventId, 2).sorted())
    }

    @Test
    fun `a WhatsApp invitation that fails for good is sent once by email`() {
        val token = register()
        val eventId = createEvent(token, primary = "WHATSAPP")
        // Imported without the consent statement: the Jikū number may not write to them, so WhatsApp fails.
        importGuests(token, eventId, "Ada,Lovelace,ada@test.example,+2250700000105", attested = false)
        send(token, eventId).andExpect(jsonPath("$.queued").value(1))

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted {
            assertEquals(mapOf("WHATSAPP" to "FAILED", "EMAIL" to "QUEUED"), statuses(token, eventId))
        }
        sweep.sweep()
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted {
            assertEquals(mapOf("WHATSAPP" to "FAILED", "EMAIL" to "SENT"), statuses(token, eventId))
        }
    }

    @Test
    fun `a primary channel the event does not offer is refused`() {
        val token = register()
        mockMvc
            .perform(
                post("/api/v1/events")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"name":"Gala","timezone":"Africa/Conakry","startDateTime":"$EVENT_YEAR-12-01T18:00:00Z",
                        "invitationChannels":["EMAIL"],"primaryChannel":"WHATSAPP"}""",
                    ),
            ).andExpect(status().isBadRequest())
    }

    private fun send(
        token: String,
        eventId: String,
        vararg channels: String,
    ) = mockMvc
        .perform(
            post("/api/v1/events/$eventId/invitations/send")
                .apply { channels.forEach { param("channels", it) } }
                .header("Authorization", "Bearer $token"),
        ).andExpect(status().isOk())

    private fun statuses(
        token: String,
        eventId: String,
    ): Map<String, String> {
        val body = invitations(token, eventId)
        val channels: List<String> = JsonPath.read(body, "$[*].channel")
        val statuses: List<String> = JsonPath.read(body, "$[*].status")
        return channels.zip(statuses).toMap()
    }

    private fun awaitChannels(
        token: String,
        eventId: String,
        count: Int,
    ): List<String> {
        var channels = emptyList<String>()
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250)).untilAsserted {
            val body = invitations(token, eventId)
            channels = JsonPath.read(body, "$[*].channel")
            assertEquals(count, channels.size)
            assertEquals(List(count) { "SENT" }, JsonPath.read<List<String>>(body, "$[*].status"))
        }
        return channels
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

    private fun importGuests(
        token: String,
        eventId: String,
        row: String,
        header: String = "firstName,lastName,email,phone",
        attested: Boolean = true,
    ) {
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .param("consentAttested", attested.toString())
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", "$header\n$row".toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(1))
    }

    private fun createEvent(
        token: String,
        primary: String?,
        fallback: Boolean = true,
    ): String {
        val primaryJson = primary?.let { "\"$it\"" } ?: "null"
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Gala","timezone":"Africa/Conakry","startDateTime":"$EVENT_YEAR-12-01T18:00:00Z",
                            "invitationChannels":["EMAIL","WHATSAPP"],"primaryChannel":$primaryJson,"channelFallback":$fallback}""",
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

    private fun register(): String {
        val email = "one-channel-${UUID.randomUUID()}@test.example"
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Org One Channel","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
