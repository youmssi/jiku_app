package com.jiku.money.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Closes the transfer requests that waited too long (JIKU-307), so an organizer
 * is never left with an old request on their billing page.
 */
@Component
class ManualPaymentExpiryJob(
    private val manualPayments: ManualPaymentService,
) {
    private val log = LoggerFactory.getLogger(ManualPaymentExpiryJob::class.java)

    @Scheduled(
        fixedDelayString = "\${billing.manual.expire-check:PT1H}",
        initialDelayString = "\${billing.manual.expire-check:PT1H}",
    )
    @SchedulerLock(name = "ManualPaymentExpiryJob.sweep")
    fun sweep() {
        val expired = manualPayments.expireStale()
        if (expired > 0) log.info("Expired {} manual payment request(s) with no confirmed transfer", expired)
    }
}
