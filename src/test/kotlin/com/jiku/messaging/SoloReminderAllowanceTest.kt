package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.money.internal.ReminderAllowanceService
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderDelivered
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.test.web.servlet.MockMvc
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals

/**
 * ADR 105: a free services plan includes a set number of WhatsApp reminders a
 * month. Past them, reminders stop by WhatsApp; a channel with an SMS fallback
 * still reaches the client by SMS.
 */
@SpringBootTest(properties = ["billing.subscription.free-plan-whatsapp-reminders=2"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SoloReminderAllowanceTest.Deliveries::class)
class SoloReminderAllowanceTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var allowance: ReminderAllowanceService

    @Autowired
    lateinit var deliveries: Deliveries

    @AfterEach
    fun clearTenant() {
        TenantContext.clear()
    }

    @Test
    fun `a free plan's reminders stop by WhatsApp once the month's allowance is used`() {
        val api = OrganizerApi(mockMvc)
        val tenantId = api.tenantId(api.register())
        val phone = "+22465" + Random.nextInt(1_000_000, 9_999_999)

        val outcomes = (1..3).map { remind(tenantId, phone, ReminderChannel.WHATSAPP) }

        assertEquals(listOf(true, true, false), outcomes.map { it.delivered })
        TenantContext.set(tenantId)
        assertEquals(2, allowance.view()?.sent)
        assertEquals(2, allowance.view()?.limit)
        TenantContext.clear()

        assertEquals(true, remind(tenantId, phone, ReminderChannel.WHATSAPP_OR_SMS).delivered)
    }

    private fun remind(
        tenantId: String,
        phone: String,
        channel: ReminderChannel,
    ): ReminderDelivered {
        val reminderId = UUID.randomUUID()
        events.publishEvent(
            ReminderDue(
                reminderId = reminderId,
                serviceId = UUID.randomUUID(),
                tenantId = tenantId,
                offsetMinutes = 60,
                clientName = "Awa",
                clientPhone = phone,
                startsAt = Instant.now().plusSeconds(3_600),
                professionalName = null,
                serviceTimezone = "Africa/Conakry",
                channel = channel,
            ),
        )
        return deliveries.of(reminderId)
    }

    /** Collects what the messaging module reports back for each reminder. */
    @Component
    class Deliveries {
        private val byId = mutableMapOf<UUID, ReminderDelivered>()

        @EventListener
        fun on(delivered: ReminderDelivered) {
            byId[delivered.reminderId] = delivered
        }

        fun of(reminderId: UUID): ReminderDelivered = requireNotNull(byId[reminderId]) { "No delivery reported for $reminderId" }
    }
}
