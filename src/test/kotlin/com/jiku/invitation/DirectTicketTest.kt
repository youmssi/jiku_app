package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.TimeUnit

/**
 * ADR 105: an event that sends tickets directly confirms each guest as their
 * invitation goes out and sends the ticket itself (with its calendar invite)
 * instead of an invitation to answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class DirectTicketTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `sending confirms the guest and delivers the ticket, not an invitation`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """
                        {"name":"Conference directe","timezone":"Africa/Conakry","startDateTime":"2026-12-01T09:00:00Z",
                        "invitationChannels":["EMAIL"],"settings":{"deliveryMode":"DIRECT_TICKET"}}
                        """.trimIndent(),
                    ).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.settings.deliveryMode").value("DIRECT_TICKET"))
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        val guestId = api.importGuest(token, eventId, "Kadiatou")

        api.post(token, "/api/v1/events/$eventId/invitations/send?channels=EMAIL", "{}").andExpect(status().isOk())

        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assert(output.out.contains("subject=\"Votre billet : Conference directe\" attachments=[invitation.ics]")) {
                "the guest did not receive their ticket"
            }
        }
        assert(!output.out.contains("subject=\"Invitation : Conference directe\"")) { "an invitation was sent instead of the ticket" }
        api
            .get(token, "/api/v1/events/$eventId/guests")
            .andExpect(jsonPath("$[?(@.id == '$guestId')].rsvpStatus").value("CONFIRMED"))
    }

    @Test
    fun `an event sends invitation links unless told otherwise`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId = api.createEvent(token)

        api.get(token, "/api/v1/events/$eventId").andExpect(jsonPath("$.settings.deliveryMode").value("LINK"))
    }
}
