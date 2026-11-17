package com.jiku.messaging.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** Removes bell notices older than the retention period (JIKU-314). */
@Component
class OrganizerNoticeRetentionJob(
    private val notices: OrganizerNoticeService,
    private val properties: OrganizerNoticeProperties,
) {
    private val log = LoggerFactory.getLogger(OrganizerNoticeRetentionJob::class.java)

    @Scheduled(
        fixedDelayString = "\${notification.notices.purge-check:PT6H}",
        initialDelayString = "\${notification.notices.purge-check:PT6H}",
    )
    @SchedulerLock(name = "OrganizerNoticeRetentionJob.purge")
    fun purge() {
        val removed = notices.purgeOlderThan(Instant.now().minus(properties.retention))
        if (removed > 0) log.info("Removed {} notification(s) past the retention period", removed)
    }
}
