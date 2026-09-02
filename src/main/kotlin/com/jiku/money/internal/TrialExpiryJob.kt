package com.jiku.money.internal

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs the trial sweep (JIKU-42): near-expiry heads-up emails first, then
 * expiry/conversion of trials past their end.
 */
@Component
class TrialExpiryJob(
    private val trialService: TrialService,
) {
    private val log = LoggerFactory.getLogger(TrialExpiryJob::class.java)

    @Scheduled(cron = "\${billing.trial.cron:0 15 * * * *}")
    fun sweep() {
        val notified = trialService.notifyExpiring()
        val processed = trialService.expireDue()
        if (notified > 0 || processed > 0) {
            log.info("Trial sweep: {} expiry notice(s) sent, {} trial(s) resolved", notified, processed)
        }
    }
}
