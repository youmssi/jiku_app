package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.EmailMessage
import com.jiku.messaging.internal.EmailQuotaCounterRepository
import com.jiku.messaging.internal.EmailQuotaExceededException
import com.jiku.messaging.internal.EmailRoutingProperties
import com.jiku.messaging.internal.EmailSender
import com.jiku.messaging.internal.RoutingEmailSender
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JIKU-62, JIKU-275: email routing through a chain of providers under a global daily cap.
 * [RoutingEmailSender] is constructed directly here (rather than switching the
 * whole app's `jiku.mail.transport` to `routing`, which would break every other
 * test's assumption of the default `log` transport) with fake provider adapters
 * so no real Resend/Brevo call is ever made, and small caps so the tests stay fast.
 *
 * Class-level `@Transactional`: [EmailQuotaCounterRepository.tryReserve] is a
 * `@Modifying` query, which JPA requires an active transaction for — in
 * production that's always supplied by the caller (`onGuestInvited`'s own
 * `@Transactional`), but a bare test method has none on its own. This also
 * rolls each test back automatically, so tests 2-4 (which all reserve against
 * the real RESEND/BREVO/GLOBAL provider constants for today's date) never leak
 * state into one another.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class EmailRoutingTest {
    @Autowired
    lateinit var quota: EmailQuotaCounterRepository

    private val message = EmailMessage(to = "guest@example.com", toName = "Ada", subject = "Hi", htmlBody = "<p>Hi</p>")

    private class RecordingSender : EmailSender {
        var sent = 0

        override fun send(
            from: String,
            message: EmailMessage,
        ) {
            sent++
        }
    }

    private fun routing(
        properties: EmailRoutingProperties,
        senders: Map<String, EmailSender>,
        overflow: EmailSender? = null,
    ) = RoutingEmailSender(RoutingEmailSender.buildChain(properties) { senders.getValue(it) }, overflow, quota, properties)

    @Test
    fun `the atomic reservation enforces the cap under repeated calls and survives being re-read`() {
        val provider = UUID.randomUUID().toString().take(16)
        val today = LocalDate.now()

        assertEquals(1, quota.tryReserve(UUID.randomUUID(), provider, today, 2))
        assertEquals(1, quota.tryReserve(UUID.randomUUID(), provider, today, 2))
        // Cap reached — a third reservation changes nothing.
        assertEquals(0, quota.tryReserve(UUID.randomUUID(), provider, today, 2))

        val row = requireNotNull(quota.findByProviderAndDay(provider, today))
        assertEquals(2, row.sentCount)
    }

    @Test
    fun `sends route through Resend first, then Brevo once Resend's cap is reached`() {
        val resend = RecordingSender()
        val brevo = RecordingSender()
        val properties = EmailRoutingProperties(resendDailyCap = 1, brevoDailyCap = 1, globalDailyCap = 3)
        val sender = routing(properties, mapOf("resend" to resend, "brevo" to brevo))

        sender.send("no-reply@hellojiku.com", message)
        assertEquals(1, resend.sent)
        assertEquals(0, brevo.sent)

        sender.send("no-reply@hellojiku.com", message)
        assertEquals(1, resend.sent)
        assertEquals(1, brevo.sent)
    }

    @Test
    fun `once both providers' daily caps are exhausted the send is refused, not attempted`() {
        val resend = RecordingSender()
        val brevo = RecordingSender()
        val properties = EmailRoutingProperties(resendDailyCap = 1, brevoDailyCap = 1, globalDailyCap = 3)
        val sender = routing(properties, mapOf("resend" to resend, "brevo" to brevo))

        sender.send("no-reply@hellojiku.com", message)
        sender.send("no-reply@hellojiku.com", message)

        assertFailsWith<EmailQuotaExceededException> { sender.send("no-reply@hellojiku.com", message) }
        assertEquals(1, resend.sent)
        assertEquals(1, brevo.sent)
    }

    @Test
    fun `the global cap binds even when neither provider's own cap is reached`() {
        val resend = RecordingSender()
        val brevo = RecordingSender()
        val properties = EmailRoutingProperties(resendDailyCap = 10, brevoDailyCap = 10, globalDailyCap = 1)
        val sender = routing(properties, mapOf("resend" to resend, "brevo" to brevo))

        sender.send("no-reply@hellojiku.com", message)
        assertFailsWith<EmailQuotaExceededException> { sender.send("no-reply@hellojiku.com", message) }
        assertTrue(resend.sent + brevo.sent == 1)
    }

    @Test
    fun `the configured order sends through useSend, then Resend, then Brevo`() {
        val usesend = RecordingSender()
        val resend = RecordingSender()
        val brevo = RecordingSender()
        val properties =
            EmailRoutingProperties(
                order = listOf("usesend", "resend", "brevo"),
                usesendDailyCap = 1,
                resendDailyCap = 1,
                brevoDailyCap = 1,
                globalDailyCap = 3,
            )
        val sender = routing(properties, mapOf("usesend" to usesend, "resend" to resend, "brevo" to brevo))

        repeat(3) { sender.send("no-reply@hellojiku.com", message) }

        assertEquals(listOf(1, 1, 1), listOf(usesend.sent, resend.sent, brevo.sent))
        assertFailsWith<EmailQuotaExceededException> { sender.send("no-reply@hellojiku.com", message) }
    }

    @Test
    fun `past every cap the paid overflow takes the send instead of refusing it`() {
        val usesend = RecordingSender()
        val paid = RecordingSender()
        val properties = EmailRoutingProperties(order = listOf("usesend"), usesendDailyCap = 1, globalDailyCap = 5)
        val sender = routing(properties, mapOf("usesend" to usesend), overflow = paid)

        repeat(3) { sender.send("no-reply@hellojiku.com", message) }

        assertEquals(1, usesend.sent)
        assertEquals(2, paid.sent)
    }

    @Test
    fun `an unknown provider in the order is refused at startup`() {
        assertFailsWith<IllegalStateException> {
            RoutingEmailSender.buildChain(EmailRoutingProperties(order = listOf("mailgun"))) { RecordingSender() }
        }
    }
}
