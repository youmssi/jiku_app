package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.NotificationService
import com.jiku.messaging.internal.SmsMessage
import com.jiku.messaging.internal.SmsSender
import com.jiku.messaging.internal.WhatsAppDeliveryException
import com.jiku.messaging.internal.WhatsAppMessage
import com.jiku.messaging.internal.WhatsAppSender
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JIKU-112: an appointment reminder goes by the service's channel, and with
 * WHATSAPP_OR_SMS an SMS takes over only when WhatsApp cannot deliver.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, ReminderChannelTest.ProvidersConfig::class)
@TestPropertySource(properties = ["jiku.whatsapp.transport=test", "jiku.sms.transport=test"])
class ReminderChannelTest {
    /** WhatsApp that can be switched to failing, and an SMS provider that records. */
    class Providers {
        val whatsAppFails =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val whatsApps = CopyOnWriteArrayList<WhatsAppMessage>()
        val smses = CopyOnWriteArrayList<SmsMessage>()
    }

    @TestConfiguration
    class ProvidersConfig {
        @Bean
        fun providers() = Providers()

        @Bean
        fun whatsAppSender(providers: Providers): WhatsAppSender =
            object : WhatsAppSender {
                override fun send(message: WhatsAppMessage) {
                    if (providers.whatsAppFails.get()) throw WhatsAppDeliveryException("unreachable")
                    providers.whatsApps += message
                }
            }

        @Bean
        fun smsSender(providers: Providers): SmsSender =
            object : SmsSender {
                override fun send(message: SmsMessage) {
                    providers.smses += message
                }
            }
    }

    @Autowired
    lateinit var notifications: NotificationService

    @Autowired
    lateinit var providers: Providers

    @BeforeEach
    fun reset() {
        providers.whatsAppFails.set(false)
        providers.whatsApps.clear()
        providers.smses.clear()
    }

    @Test
    fun `WHATSAPP_OR_SMS falls back to SMS only when WhatsApp cannot deliver`() {
        assertTrue(send(ReminderChannel.WHATSAPP_OR_SMS).delivered)
        assertEquals(1, providers.whatsApps.size)
        assertTrue(providers.smses.isEmpty())

        providers.whatsAppFails.set(true)
        assertTrue(send(ReminderChannel.WHATSAPP_OR_SMS).delivered)
        assertEquals("+224620000002", providers.smses.single().to)
    }

    @Test
    fun `WHATSAPP alone never sends an SMS`() {
        providers.whatsAppFails.set(true)

        assertFalse(send(ReminderChannel.WHATSAPP).delivered)
        assertTrue(providers.smses.isEmpty())
    }

    @Test
    fun `SMS goes by SMS only`() {
        assertTrue(send(ReminderChannel.SMS).delivered)
        assertTrue(providers.whatsApps.isEmpty())
        assertEquals(1, providers.smses.size)
    }

    private fun send(channel: ReminderChannel) =
        TenantContext.withTenant("reminder-channel-tenant") {
            notifications.deliverAppointmentReminder(
                ReminderDue(
                    reminderId = UUID.randomUUID(),
                    serviceId = UUID.randomUUID(),
                    tenantId = "reminder-channel-tenant",
                    offsetMinutes = 120,
                    clientName = "Kadiatou",
                    clientPhone = "+224620000002",
                    startsAt = Instant.parse("2026-12-01T09:00:00Z"),
                    professionalName = "Binta",
                    serviceTimezone = "Africa/Conakry",
                    channel = channel,
                ),
            )
        }
}
