package com.jiku.catalog.internal

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
 * Applies the retention policy to the clients of services (JIKU-267): once an
 * appointment is older than the retention window (`compliance.retention.days`,
 * the same as for event guests), its client's name and number are removed.
 * The reservation itself stays, so occupancy and history remain counted.
 * Plain SQL: the reservations of every organization are swept, not the current one's.
 */
@Component
class ServiceClientRetentionJob(
    private val jdbc: JdbcTemplate,
    private val properties: RetentionProperties,
) {
    private val log = LoggerFactory.getLogger(ServiceClientRetentionJob::class.java)

    @Scheduled(cron = "\${compliance.retention.cron:0 30 3 * * *}")
    @SchedulerLock(name = "ServiceClientRetentionJob.anonymize")
    fun anonymize() {
        val count = anonymizeBefore(Instant.now().minus(Duration.ofDays(properties.days)))
        if (count > 0) log.info("Retention policy removed the client details of {} past reservation(s)", count)
    }

    fun anonymizeBefore(cutoff: Instant): Int =
        jdbc.update(
            "UPDATE service_reservation SET client_name = NULL, client_phone = NULL " +
                "WHERE starts_at < ? AND (client_name IS NOT NULL OR client_phone IS NOT NULL)",
            Timestamp.from(cutoff),
        )
}
