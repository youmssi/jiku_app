package com.jiku.notification

import com.jiku.TestcontainersConfiguration
import com.jiku.notification.internal.NotificationLog
import com.jiku.notification.internal.NotificationLogRepository
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class NotificationOrchestrationTest {
    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var logs: NotificationLogRepository

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `delivering an invitation event records an audited send`() {
        TenantContext.set("tenant-notify")
        val invitationId = UUID.randomUUID()

        events.publishEvent(
            GuestInvitedEvent(
                invitationId = invitationId,
                tenantId = "tenant-notify",
                eventId = UUID.randomUUID(),
                channel = GuestInvitedEvent.CHANNEL_EMAIL,
                recipient = "guest@example.com",
                recipientName = "Ada Lovelace",
                eventName = "Gala",
                eventWhen = "Fri, 3 Jul 2026 at 19:00",
                eventLocation = "Abidjan",
                organizerName = "Org",
                primaryColor = "#1E293B",
                logoUrl = null,
                invitationUrl = "http://localhost:3000/invitation/abc",
            ),
        )

        // The listener is synchronous, so the audit row is persisted by now.
        val records = logs.findByReferenceId(invitationId)
        assertEquals(1, records.size, "exactly one delivery attempt should be logged")
        assertTrue(records.all { it.status == NotificationLog.STATUS_SENT })
        assertEquals("guest@example.com", records.first().recipient)
        assertEquals(GuestInvitedEvent.CHANNEL_EMAIL, records.first().channel)
    }
}
