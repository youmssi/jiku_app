package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.EmailMessage
import com.jiku.messaging.internal.EmailQuotaCounter
import com.jiku.messaging.internal.EmailQuotaCounterRepository
import com.jiku.messaging.internal.EmailQuotaExceededException
import com.jiku.messaging.internal.EmailRoutingProperties
import com.jiku.messaging.internal.EmailSender
import com.jiku.messaging.internal.RoutingEmailSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
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
 * No test transaction (JIKU-302): production sends run on async threads without
 * one, and a class-level `@Transactional` here once hid that the reservation
 * failed there. The counters are cleared around each test instead, since every
 * test reserves against today's real provider names.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class EmailRoutingTest {
    @Autowired
    lateinit var quota: EmailQuotaCounterRepository

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    @AfterEach
    fun clearCounters() {
        quota.deleteAllInBatch()
    }

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
    fun `a send from a thread without a transaction reserves and goes out`() {
        val resend = RecordingSender()
        val sender =
            routing(
                EmailRoutingProperties(resendDailyCap = 5, brevoDailyCap = 5, globalDailyCap = 5),
                mapOf(
                    "resend" to resend,
                    "brevo" to RecordingSender(),
                ),
            )

        val worker = Thread { sender.send("no-reply@hellojiku.com", message) }
        var failure: Throwable? = null
        worker.setUncaughtExceptionHandler { _, ex -> failure = ex }
        worker.start()
        worker.join()

        assertEquals(null, failure)
        assertEquals(1, resend.sent)
        assertEquals(1, requireNotNull(quota.findByProviderAndDay(EmailQuotaCounter.PROVIDER_RESEND, LocalDate.now())).sentCount)
    }

    @Test
    fun `a send from inside a read-only transaction still reserves`() {
        val resend = RecordingSender()
        val sender =
            routing(
                EmailRoutingProperties(resendDailyCap = 5, brevoDailyCap = 5, globalDailyCap = 5),
                mapOf(
                    "resend" to resend,
                    "brevo" to RecordingSender(),
                ),
            )

        TransactionTemplate(transactionManager).apply { isReadOnly = true }.executeWithoutResult {
            sender.send("no-reply@hellojiku.com", message)
        }

        assertEquals(1, resend.sent)
    }

    @Test
    fun `an unknown provider in the order is refused at startup`() {
        assertFailsWith<IllegalStateException> {
            RoutingEmailSender.buildChain(EmailRoutingProperties(order = listOf("mailgun"))) { RecordingSender() }
        }
    }
}
