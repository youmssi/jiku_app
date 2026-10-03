package com.jiku.invitation

import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationRepository
import com.jiku.invitation.internal.InvitationStatus
import com.jiku.invitation.internal.NotificationQueueSweepJob
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

/** JIKU-215: an invitation left PENDING by a restart is handed to the sender again, a recent one is not. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class StalePendingInvitationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var invitations: InvitationRepository

    @Autowired
    lateinit var sweep: NotificationQueueSweepJob

    @AfterEach
    fun clear() = TenantContext.clear()

    @Test
    fun `the sweep hands over again an invitation lost by a restart, and only that one`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId = api.createEvent(token)
        api.publish(token, eventId)
        api.importGuest(token, eventId, "Mariam")
        api.importGuest(token, eventId, "Fanta")
        api.post(token, "/api/v1/events/$eventId/invitations/send?channels=EMAIL", "{}").andExpect(status().isOk())
        TenantContext.set(api.tenantId(token))
        await().pollInSameThread().atMost(Duration.ofSeconds(15)).until {
            invitations.findAll().filter { it.eventId.toString() == eventId }.all { it.status == InvitationStatus.SENT }
        }

        val (lost, inFlight) = invitations.findAll().filter { it.eventId.toString() == eventId }
        lost.status = InvitationStatus.PENDING
        lost.dispatchedAt = Instant.now().minus(2, ChronoUnit.HOURS)
        inFlight.status = InvitationStatus.PENDING
        inFlight.dispatchedAt = Instant.now()
        invitations.saveAll(listOf(lost, inFlight))
        TenantContext.clear()

        sweep.sweep()

        TenantContext.set(api.tenantId(token))
        await().pollInSameThread().atMost(Duration.ofSeconds(15)).until {
            invitations.findById(requireNotNull(lost.id)).orElseThrow().status == InvitationStatus.SENT
        }
        assertEquals(InvitationStatus.PENDING, invitations.findById(requireNotNull(inFlight.id)).orElseThrow().status)
    }
}
