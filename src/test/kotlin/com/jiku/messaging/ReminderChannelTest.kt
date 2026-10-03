package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.NotificationService
import com.jiku.messaging.internal.SmsMessage
import com.jiku.messaging.internal.SmsSender
import com.jiku.messaging.internal.WhatsAppDeliveryException
import com.jiku.messaging.internal.WhatsAppDeliveryStatusService
import com.jiku.messaging.internal.WhatsAppMessage
import com.jiku.messaging.internal.WhatsAppSender
import com.jiku.messaging.internal.WhatsAppSentMessageRepository
import com.jiku.messaging.internal.WhatsAppStatusUpdate
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantContext
import com.jiku.support.TestDates.EVENT_YEAR
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
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
        val wamids = CopyOnWriteArrayList<String>()
        val sentInTransaction = CopyOnWriteArrayList<Boolean>()
    }

    @TestConfiguration
    class ProvidersConfig {
        @Bean
        fun providers() = Providers()

        @Bean
        fun whatsAppSender(providers: Providers): WhatsAppSender =
            object : WhatsAppSender {
                override fun send(message: WhatsAppMessage): String? {
                    if (providers.whatsAppFails.get()) throw WhatsAppDeliveryException("unreachable")
                    providers.sentInTransaction += TransactionSynchronizationManager.isActualTransactionActive()
                    providers.whatsApps += message
                    return "wamid.${UUID.randomUUID()}".also { providers.wamids += it }
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

    @Autowired
    lateinit var deliveries: WhatsAppDeliveryStatusService

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val transactions by lazy { TransactionTemplate(transactionManager) }

    @Autowired
    lateinit var sentMessages: WhatsAppSentMessageRepository

    @BeforeEach
    fun reset() {
        providers.whatsAppFails.set(false)
        providers.whatsApps.clear()
        providers.smses.clear()
        providers.wamids.clear()
        providers.sentInTransaction.clear()
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

    @Test
    fun `a reminder Meta later cannot deliver goes by SMS once, when its channel allows it`() {
        assertTrue(send(ReminderChannel.WHATSAPP_OR_SMS).delivered)
        val wamid = providers.wamids.single()

        deliveries.onStatus(WhatsAppStatusUpdate(wamid, "failed", 131026, "Message undeliverable"))
        deliveries.onStatus(WhatsAppStatusUpdate(wamid, "failed", 131026, "Message undeliverable"))

        assertEquals("+224620000002", providers.smses.single().to)
        assertTrue(
            providers.smses
                .single()
                .body
                .startsWith("Bonjour Kadiatou"),
        )
        val message = sentMessages.findById(wamid).orElseThrow()
        assertEquals("FAILED", message.status)
        assertEquals(131026, message.errorCode)
    }

    @Test
    fun `a WhatsApp-only reminder Meta cannot deliver is recorded failed, without SMS`() {
        send(ReminderChannel.WHATSAPP)

        deliveries.onStatus(WhatsAppStatusUpdate(providers.wamids.single(), "failed", 131049, "Not delivered"))

        assertTrue(providers.smses.isEmpty())
        assertEquals("FAILED", sentMessages.findById(providers.wamids.single()).orElseThrow().status)
    }

    @Test
    fun `a late status never steps a message back`() {
        send(ReminderChannel.WHATSAPP)
        val wamid = providers.wamids.single()

        deliveries.onStatus(WhatsAppStatusUpdate(wamid, "read"))
        deliveries.onStatus(WhatsAppStatusUpdate(wamid, "delivered"))
        deliveries.onStatus(WhatsAppStatusUpdate("wamid.unknown", "failed", 1, "x"))

        assertEquals("READ", sentMessages.findById(wamid).orElseThrow().status)
    }

    @Test
    fun `a reminder due inside a transaction is sent after it commits, holding no database connection`() {
        val reminder = due(ReminderChannel.WHATSAPP)

        transactions.executeWithoutResult {
            events.publishEvent(reminder)
            assertTrue(providers.whatsApps.isEmpty(), "sent before the transaction committed")
        }

        await().atMost(Duration.ofSeconds(10)).until { providers.whatsApps.isNotEmpty() }
        assertEquals(listOf(false), providers.sentInTransaction)
    }

    private fun send(channel: ReminderChannel) =
        TenantContext.withTenant("reminder-channel-tenant") { notifications.deliverAppointmentReminder(due(channel)) }

    private fun due(channel: ReminderChannel) =
        ReminderDue(
            reminderId = UUID.randomUUID(),
            serviceId = UUID.randomUUID(),
            tenantId = "reminder-channel-tenant",
            offsetMinutes = 120,
            clientName = "Kadiatou",
            clientPhone = "+224620000002",
            startsAt = Instant.parse("${EVENT_YEAR}-12-01T09:00:00Z"),
            professionalName = "Binta",
            serviceTimezone = "Africa/Conakry",
            channel = channel,
        )
}
