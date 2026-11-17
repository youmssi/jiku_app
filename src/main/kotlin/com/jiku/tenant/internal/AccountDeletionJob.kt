package com.jiku.tenant.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Erases the accounts whose deletion grace period is over (JIKU-316). */
@Component
class AccountDeletionJob(
    private val deletion: AccountDeletionService,
) {
    private val log = LoggerFactory.getLogger(AccountDeletionJob::class.java)

    @Scheduled(
        fixedDelayString = "\${auth.account-deletion.check:PT1H}",
        initialDelayString = "\${auth.account-deletion.check:PT1H}",
    )
    @SchedulerLock(name = "AccountDeletionJob.run")
    fun run() {
        val done = deletion.runDue()
        if (done > 0) log.info("Erased {} account(s) at the end of their deletion period", done)
    }
}
