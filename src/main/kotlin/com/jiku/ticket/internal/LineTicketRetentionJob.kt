package com.jiku.ticket.internal

import com.jiku.shared.RetentionProperties
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Applies the retention policy to the clients of the day line (JIKU-267): a
 * service ticket older than the retention window (`compliance.retention.days`)
 * loses its client's name and number; the ticket, its rank and its payment stay.
 * Plain SQL: the tickets of every organization are swept, not the current one's.
 */
@Component
class LineTicketRetentionJob(
    private val jdbc: JdbcTemplate,
    private val properties: RetentionProperties,
) {
    private val log = LoggerFactory.getLogger(LineTicketRetentionJob::class.java)

    @Scheduled(cron = "\${compliance.retention.cron:0 30 3 * * *}")
    @SchedulerLock(name = "LineTicketRetentionJob.anonymize")
    fun anonymize() {
        val count = anonymizeBefore(Instant.now().minus(Duration.ofDays(properties.days)))
        if (count > 0) log.info("Retention policy removed the client details of {} past line ticket(s)", count)
    }

    fun anonymizeBefore(cutoff: Instant): Int =
        jdbc.update(
            "UPDATE ticket SET client_name = NULL, client_phone = NULL " +
                "WHERE COALESCE(starts_at, issued_at) < ? AND (client_name IS NOT NULL OR client_phone IS NOT NULL)",
            Timestamp.from(cutoff),
        )
}
