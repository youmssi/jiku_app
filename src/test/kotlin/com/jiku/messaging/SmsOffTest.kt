package com.jiku.messaging

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.NotificationLogRepository
import com.jiku.messaging.internal.NotificationService
import com.jiku.messaging.internal.WhatsAppDeliveryException
import com.jiku.messaging.internal.WhatsAppDeliveryStatusService
import com.jiku.messaging.internal.WhatsAppMessage
import com.jiku.messaging.internal.WhatsAppSender
import com.jiku.messaging.internal.WhatsAppSentMessageRepository
import com.jiku.messaging.internal.WhatsAppStatusUpdate
import com.jiku.shared.PhoneCodeRequested
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantContext
import com.jiku.support.TestDates.EVENT_YEAR
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JIKU-261: with `jiku.sms.transport=off` no SMS is ever attempted — the
 * WhatsApp fallback is skipped, an SMS-only message is recorded as not sent
 * with its reason, and screens learn that SMS is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, SmsOffTest.WhatsAppConfig::class)
@TestPropertySource(properties = ["jiku.whatsapp.transport=test", "jiku.sms.transport=off"])
class SmsOffTest {
    class WhatsApp {
        val fails = AtomicBoolean(false)
        val sent = CopyOnWriteArrayList<WhatsAppMessage>()
        val wamids = CopyOnWriteArrayList<String>()
    }

    @TestConfiguration
    class WhatsAppConfig {
        @Bean
        fun whatsApp() = WhatsApp()

        @Bean
        fun whatsAppSender(whatsApp: WhatsApp): WhatsAppSender =
            object : WhatsAppSender {
                override fun send(message: WhatsAppMessage): String? {
                    if (whatsApp.fails.get()) throw WhatsAppDeliveryException("unreachable")
                    whatsApp.sent += message
                    return "wamid.${UUID.randomUUID()}".also { whatsApp.wamids += it }
                }
            }
    }

    @Autowired
    lateinit var notifications: NotificationService

    @Autowired
    lateinit var whatsApp: WhatsApp

    @Autowired
    lateinit var deliveries: WhatsAppDeliveryStatusService

    @Autowired
    lateinit var sentMessages: WhatsAppSentMessageRepository

    @Autowired
    lateinit var logs: NotificationLogRepository

    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun reset() {
        whatsApp.fails.set(false)
        whatsApp.sent.clear()
        whatsApp.wamids.clear()
    }

    @Test
    fun `WHATSAPP_OR_SMS keeps the WhatsApp outcome and tries no SMS`() {
        whatsApp.fails.set(true)
        val due = due(ReminderChannel.WHATSAPP_OR_SMS)

        val outcome = send(due)

        assertFalse(outcome.delivered)
        assertTrue(channelsLogged(due.reminderId).none { it == "SMS" })
    }

    @Test
    fun `an SMS-only message is recorded as not sent, with the reason`() {
        val due = due(ReminderChannel.SMS)

        val outcome = send(due)

        assertFalse(outcome.delivered)
        assertEquals(0, outcome.attempts)
        val log = TenantContext.withTenant(TENANT) { logs.findAll().single { it.referenceId == due.reminderId } }
        assertEquals("SMS", log.channel)
        assertEquals("FAILED", log.status)
        assertEquals("Not sent: SMS is turned off on this platform", log.error)
    }

    @Test
    fun `a WhatsApp message Meta later cannot deliver keeps no SMS fallback`() {
        assertTrue(send(due(ReminderChannel.WHATSAPP_OR_SMS)).delivered)
        val wamid = whatsApp.wamids.single()
        assertNull(sentMessages.findById(wamid).orElseThrow().smsFallback)

        deliveries.onStatus(WhatsAppStatusUpdate(wamid, "failed", 131026, "Message undeliverable"))

        assertEquals("FAILED", sentMessages.findById(wamid).orElseThrow().status)
    }

    @Test
    fun `a phone code still goes on WhatsApp`() {
        val requested = PhoneCodeRequested(tenantId = TENANT, phone = "+224620000019", code = "481902")

        assertTrue(TenantContext.withTenant(TENANT) { notifications.deliverPhoneCode(requested) }.delivered)
        assertEquals(1, whatsApp.sent.size)
    }

    @Test
    fun `screens learn that SMS is unavailable`() {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Org SMS","email":"sms-${UUID.randomUUID()}@test.example","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val token: String = JsonPath.read(body, "$.accessToken")

        mockMvc
            .perform(get("/api/v1/messaging/channels").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sms").value(false))
        mockMvc.perform(get("/api/v1/messaging/channels")).andExpect(status().is4xxClientError())
    }

    private fun channelsLogged(referenceId: UUID) =
        TenantContext.withTenant(TENANT) { logs.findAll().filter { it.referenceId == referenceId }.map { it.channel } }

    private fun send(due: ReminderDue) = TenantContext.withTenant(TENANT) { notifications.deliverAppointmentReminder(due) }

    private fun due(channel: ReminderChannel) =
        ReminderDue(
            reminderId = UUID.randomUUID(),
            serviceId = UUID.randomUUID(),
            tenantId = TENANT,
            offsetMinutes = 120,
            clientName = "Kadiatou",
            clientPhone = "+224620000012",
            startsAt = Instant.parse("${EVENT_YEAR}-12-01T09:00:00Z"),
            professionalName = "Binta",
            serviceTimezone = "Africa/Conakry",
            channel = channel,
        )

    private companion object {
        const val TENANT = "sms-off-tenant"
    }
}
