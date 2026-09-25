package com.jiku.invitation

import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * JIKU-129: a guest who confirms receives their ticket by email, with a
 * calendar invite attached; confirming again sends nothing more.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class TicketConfirmationEmailTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tokenService: InvitationTokenService

    @Test
    fun `confirming sends the ticket with a calendar invite, once`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val tenantId = api.tenantId(token)
        val eventId = api.createEvent(token)
        api.publish(token, eventId)
        val guestId = api.importGuest(token, eventId, "Mariam")
        val rsvp = tokenService.issue(UUID.fromString(guestId), UUID.fromString(eventId), tenantId)

        mockMvc.perform(post("/api/v1/rsvp/$rsvp/confirm")).andExpect(status().isOk())

        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assert(output.out.contains("subject=\"Votre billet : Test Event\" attachments=[invitation.ics]")) {
                "the confirmed guest did not receive their ticket email"
            }
        }

        mockMvc.perform(post("/api/v1/rsvp/$rsvp/confirm")).andExpect(status().isOk())
        Thread.sleep(1_000)
        assert(output.out.split("subject=\"Votre billet : Test Event\"").size == 2) { "a second confirmation sent another email" }
    }
}
