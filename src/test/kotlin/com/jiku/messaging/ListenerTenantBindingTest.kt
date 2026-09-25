package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderDue
import com.jiku.shared.TicketConfirmedNotice
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * A messaging listener reached with no transaction of the event's tenant (a
 * scheduler, a direct publish) still writes its delivery log under that
 * tenant, never under the unresolved one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ListenerTenantBindingTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `delivery logs land on the organization the event names`() {
        val tenantId = OrganizerApi(mockMvc).let { it.tenantId(it.register()) }
        val phone = "+22466" + Random.nextInt(1_000_000, 9_999_999)
        val email = "guest-${UUID.randomUUID()}@test.example"

        events.publishEvent(
            ReminderDue(
                reminderId = UUID.randomUUID(),
                serviceId = UUID.randomUUID(),
                tenantId = tenantId,
                offsetMinutes = 60,
                clientName = "Awa",
                clientPhone = phone,
                startsAt = Instant.now().plusSeconds(3_600),
                professionalName = null,
                serviceTimezone = "Africa/Conakry",
                channel = ReminderChannel.WHATSAPP,
            ),
        )
        events.publishEvent(
            TicketConfirmedNotice(
                guestId = UUID.randomUUID(),
                tenantId = tenantId,
                eventId = UUID.randomUUID(),
                recipient = email,
                recipientName = "Awa Camara",
                eventName = "Gala",
                eventStart = null,
                eventEnd = null,
                eventTimezone = "Africa/Conakry",
                eventLocation = null,
                categoryName = null,
                organizerName = "Clinique Horizon",
                primaryColor = "#1E293B",
                logoUrl = null,
                ticketUrl = "https://jiku.test/invitation/abc/ticket",
            ),
        )

        assertEquals(listOf(tenantId), tenantsOfLogsTo(phone))
        assertEquals(listOf(tenantId), tenantsOfLogsTo(email))
    }

    private fun tenantsOfLogsTo(recipient: String): List<String> =
        jdbc
            .queryForList(
                "SELECT DISTINCT tenant_id FROM notification_log WHERE recipient = ?",
                String::class.java,
                recipient,
            ).filterNotNull()
}
