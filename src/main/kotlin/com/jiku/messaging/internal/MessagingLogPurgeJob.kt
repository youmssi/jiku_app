package com.jiku.messaging.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** How long sending records are kept (JIKU-216); 0 turns the purge off. */
@ConfigurationProperties(prefix = "jiku.messaging.retention")
data class MessagingRetentionProperties(
    val days: Long = 180,
    /** Rows deleted per statement, so the purge never holds a long lock. */
    val batchSize: Int = 5_000,
)

/**
 * Deletes, every night, the sending records older than the retention period
 * (JIKU-216): attempts, WhatsApp messages and reply threads, soft bounces and
 * daily email counters. They hold recipients' addresses and numbers, and every
 * reputation check reads them, so they must not grow forever.
 *
 * Kept whatever their age:
 * - hard bounces, which stop Jikū from writing again to that address, and
 *   complaints, the record of who objected;
 * - each organization's first delivered message (back-office activation funnel);
 * - WhatsApp message costs, which feed billing, and STOP requests.
 *
 * Plain SQL: the records of every organization are purged, not the current one's.
 */
@Component
@EnableConfigurationProperties(MessagingRetentionProperties::class)
class MessagingLogPurgeJob(
    private val jdbc: JdbcTemplate,
    private val properties: MessagingRetentionProperties,
) {
    private val log = LoggerFactory.getLogger(MessagingLogPurgeJob::class.java)

    @Scheduled(cron = "\${jiku.messaging.retention.cron:0 45 3 * * *}")
    @SchedulerLock(name = "MessagingLogPurgeJob.purge")
    fun purge() {
        if (properties.days <= 0) return
        val deleted = purgeBefore(Instant.now().minus(properties.days, ChronoUnit.DAYS))
        if (deleted.values.any { it > 0 }) log.info("Purged sending records older than {} days: {}", properties.days, deleted)
    }

    /** Deletes the records older than [cutoff] and returns how many per table. */
    fun purgeBefore(cutoff: Instant): Map<String, Int> {
        val at = Timestamp.from(cutoff)
        return mapOf(
            "notification_log" to
                inBatches(
                    "DELETE FROM notification_log WHERE id IN (SELECT l.id FROM notification_log l " +
                        "WHERE l.created_at < ? AND NOT (l.status = 'SENT' AND l.created_at = " +
                        "(SELECT MIN(f.created_at) FROM notification_log f WHERE f.tenant_id = l.tenant_id AND f.status = 'SENT')) " +
                        "LIMIT ?)",
                    at,
                ),
            "whatsapp_message" to
                inBatches("DELETE FROM whatsapp_message WHERE wamid IN (SELECT wamid FROM whatsapp_message WHERE sent_at < ? LIMIT ?)", at),
            "whatsapp_thread" to
                inBatches(
                    "DELETE FROM whatsapp_thread WHERE invitation_id IN (SELECT invitation_id FROM whatsapp_thread WHERE sent_at < ? LIMIT ?)",
                    at,
                ),
            "email_feedback" to
                inBatches(
                    "DELETE FROM email_feedback WHERE id IN (SELECT id FROM email_feedback " +
                        "WHERE created_at < ? AND feedback_type NOT IN ('${EmailFeedback.TYPE_HARD_BOUNCE}', " +
                        "'${EmailFeedback.TYPE_COMPLAINT}') LIMIT ?)",
                    at,
                ),
            "email_quota_counter" to
                jdbc.update("DELETE FROM email_quota_counter WHERE day < ?", LocalDate.ofInstant(cutoff, ZoneOffset.UTC)),
        )
    }

    private fun inBatches(
        sql: String,
        cutoff: Timestamp,
    ): Int {
        var total = 0
        do {
            val deleted = jdbc.update(sql, cutoff, properties.batchSize)
            total += deleted
        } while (deleted == properties.batchSize)
        return total
    }
}
