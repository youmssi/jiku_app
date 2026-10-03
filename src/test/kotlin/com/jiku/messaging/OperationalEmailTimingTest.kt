package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.EmailMessage
import com.jiku.messaging.internal.EmailSender
import com.jiku.shared.AccountNotice
import com.jiku.shared.OpsAlert
import org.awaitility.Awaitility.await
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * JIKU-216: account and notice emails leave the request: they go out after the
 * transaction commits, on another thread, and never for a change rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class, OperationalEmailTimingTest.MailboxConfig::class)
@TestPropertySource(properties = ["jiku.mail.transport=test", "notification.sales.email=ops@jiku.test"])
class OperationalEmailTimingTest {
    /** One email sent: to whom, what, from which thread, inside a transaction or not. */
    data class Sent(
        val to: String,
        val subject: String,
        val thread: String,
        val inTransaction: Boolean,
    )

    class Mailbox {
        val sent = CopyOnWriteArrayList<Sent>()

        fun to(address: String) = sent.firstOrNull { it.to == address }
    }

    @TestConfiguration
    class MailboxConfig {
        @Bean
        fun mailbox() = Mailbox()

        @Bean
        fun emailSender(mailbox: Mailbox): EmailSender =
            object : EmailSender {
                override fun send(
                    from: String,
                    message: EmailMessage,
                ) {
                    mailbox.sent +=
                        Sent(
                            to = message.to,
                            subject = message.subject,
                            thread = Thread.currentThread().name,
                            inTransaction = TransactionSynchronizationManager.isActualTransactionActive(),
                        )
                }
            }
    }

    @Autowired
    lateinit var mailbox: Mailbox

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val transactions by lazy { TransactionTemplate(transactionManager) }

    @Test
    fun `a password reset is mailed after the transaction commits, off the request thread`() {
        val email = "reset-${UUID.randomUUID()}@jiku.test"

        transactions.executeWithoutResult {
            events.publishEvent(reset(email))
            assertNull(mailbox.to(email), "mailed before the transaction committed")
        }

        await().atMost(Duration.ofSeconds(10)).until { mailbox.to(email) != null }
        val sent = requireNotNull(mailbox.to(email))
        assertNotEquals(Thread.currentThread().name, sent.thread)
        assertFalse(sent.inTransaction)
    }

    @Test
    fun `no email announces a change that was rolled back`() {
        val email = "rolled-back-${UUID.randomUUID()}@jiku.test"

        transactions.executeWithoutResult {
            events.publishEvent(reset(email))
            it.setRollbackOnly()
        }
        events.publishEvent(reset("after-$email"))

        await().atMost(Duration.ofSeconds(10)).until { mailbox.to("after-$email") != null }
        assertNull(mailbox.to(email))
    }

    @Test
    fun `an ops alert is mailed even when its transaction rolls back`() {
        val subject = "Rolled back ${UUID.randomUUID()}"

        transactions.executeWithoutResult {
            events.publishEvent(OpsAlert(subject = subject, message = "The action failed"))
            it.setRollbackOnly()
        }

        await().atMost(Duration.ofSeconds(10)).until { mailbox.sent.any { it.subject == subject } }
        assertEquals("ops@jiku.test", mailbox.sent.single { it.subject == subject }.to)
    }

    private fun reset(email: String) =
        AccountNotice(kind = AccountNotice.KIND_PASSWORD_RESET, email = email, actionUrl = "https://jiku.test/reset?token=t")
}
