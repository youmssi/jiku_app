package com.jiku.backoffice.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * Deletes the prospects who wrote to Jikū more than `backoffice.prospects.retention-days`
 * ago (JIKU-267, default twelve months): a prospect who has not become a
 * customer by then is no longer followed up, and the privacy policy promises
 * not to keep their details. 0 turns the purge off.
 */
@Component
class ProspectRetentionJob(
    private val jdbc: JdbcTemplate,
    @Value("\${backoffice.prospects.retention-days:365}") private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(ProspectRetentionJob::class.java)

    @Scheduled(cron = "\${backoffice.prospects.retention-cron:0 40 3 * * *}")
    @SchedulerLock(name = "ProspectRetentionJob.purge")
    fun purge() {
        if (retentionDays <= 0) return
        val count = purgeBefore(Instant.now().minus(Duration.ofDays(retentionDays)))
        if (count > 0) log.info("Deleted {} prospect(s) older than {} days", count, retentionDays)
    }

    fun purgeBefore(cutoff: Instant): Int = jdbc.update("DELETE FROM prospect_lead WHERE created_at < ?", Timestamp.from(cutoff))
}
