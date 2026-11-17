package com.jiku.money.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Reminds organizers of gifts about to lapse, then expires the ones never activated (JIKU-315). */
@Component
class GiftExpiryJob(
    private val gifts: GiftService,
) {
    private val log = LoggerFactory.getLogger(GiftExpiryJob::class.java)

    @Scheduled(
        fixedDelayString = "\${billing.gifts.check:PT1H}",
        initialDelayString = "\${billing.gifts.check:PT1H}",
    )
    @SchedulerLock(name = "GiftExpiryJob.sweep")
    fun sweep() {
        val reminded = gifts.remindUnclaimed()
        val expired = gifts.expireUnclaimed()
        if (reminded + expired > 0) log.info("Gifts: {} reminded, {} expired unclaimed", reminded, expired)
    }
}
