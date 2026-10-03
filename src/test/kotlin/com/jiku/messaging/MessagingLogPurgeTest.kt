package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MessagingLogPurgeJob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** JIKU-216: old sending records go; what protects recipients and billing stays. */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MessagingLogPurgeTest {
    @Autowired
    lateinit var purge: MessagingLogPurgeJob

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val old = Instant.now().minus(200, ChronoUnit.DAYS)
    private val recent = Instant.now().minus(10, ChronoUnit.DAYS)
    private val cutoff = Instant.now().minus(180, ChronoUnit.DAYS)

    @Test
    fun `old attempts are deleted, except each organization's first delivered message`() {
        val tenant = "purge-${UUID.randomUUID()}"
        val first = log(tenant, "SENT", old.minus(10, ChronoUnit.DAYS))
        val oldSent = log(tenant, "SENT", old)
        val oldFailed = log(tenant, "FAILED", old)
        val recentSent = log(tenant, "SENT", recent)

        purge.purgeBefore(cutoff)

        assertThat(remaining("notification_log", "id", listOf(first, oldSent, oldFailed, recentSent)))
            .containsExactlyInAnyOrder(first, recentSent)
    }

    @Test
    fun `hard bounces and complaints are kept, old soft bounces go`() {
        val recipient = "purge-${UUID.randomUUID()}@jiku.test"
        val hard = feedback(recipient, "HARD_BOUNCE", old)
        val complaint = feedback(recipient, "COMPLAINT", old)
        val soft = feedback(recipient, "SOFT_BOUNCE", old)
        val recentSoft = feedback(recipient, "SOFT_BOUNCE", recent)

        purge.purgeBefore(cutoff)

        assertThat(remaining("email_feedback", "id", listOf(hard, complaint, soft, recentSoft)))
            .containsExactlyInAnyOrder(hard, complaint, recentSoft)
    }

    @Test
    fun `old WhatsApp messages, threads and email counters go`() {
        val oldMessage = whatsAppMessage(old)
        val recentMessage = whatsAppMessage(recent)
        val oldThread = thread(old)
        val recentThread = thread(recent)
        val oldDay = LocalDate.ofInstant(old, ZoneOffset.UTC)
        jdbc.update(
            "INSERT INTO email_quota_counter (id, provider, day, sent_count) VALUES (?, 'PURGE', ?, 3) ON CONFLICT DO NOTHING",
            UUID.randomUUID(),
            oldDay,
        )

        purge.purgeBefore(cutoff)

        assertThat(remaining("whatsapp_message", "wamid", listOf(oldMessage, recentMessage))).containsExactly(recentMessage)
        assertThat(remaining("whatsapp_thread", "invitation_id", listOf(oldThread, recentThread))).containsExactly(recentThread)
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM email_quota_counter WHERE provider = 'PURGE' AND day = ?", Long::class.java, oldDay),
        ).isZero()
    }

    private fun log(
        tenant: String,
        status: String,
        at: Instant,
    ): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO notification_log (id, tenant_id, channel, recipient, status, attempt, created_at) " +
                    "VALUES (?, ?, 'EMAIL', 'guest@jiku.test', ?, 1, ?)",
                it,
                tenant,
                status,
                Timestamp.from(at),
            )
        }

    private fun feedback(
        recipient: String,
        type: String,
        at: Instant,
    ): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO email_feedback (id, recipient, feedback_type, created_at) VALUES (?, ?, ?, ?)",
                it,
                recipient,
                type,
                Timestamp.from(at),
            )
        }

    private fun whatsAppMessage(at: Instant): String =
        "wamid.${UUID.randomUUID()}".also {
            jdbc.update(
                "INSERT INTO whatsapp_message (wamid, recipient, own_number, invitation, status, sent_at, updated_at) " +
                    "VALUES (?, '224620000000', false, true, 'SENT', ?, ?)",
                it,
                Timestamp.from(at),
                Timestamp.from(at),
            )
        }

    private fun thread(at: Instant): UUID =
        UUID.randomUUID().also {
            jdbc.update(
                "INSERT INTO whatsapp_thread (invitation_id, tenant_id, phone, language, sent_at) VALUES (?, 'purge', '224620000000', 'fr', ?)",
                it,
                Timestamp.from(at),
            )
        }

    private fun <T : Any> remaining(
        table: String,
        key: String,
        ids: List<T>,
    ): List<Any> =
        ids.filter { id ->
            jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $key = ?", Long::class.java, id) == 1L
        }
}
