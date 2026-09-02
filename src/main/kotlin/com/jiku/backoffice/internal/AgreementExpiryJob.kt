package com.jiku.backoffice.internal

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Daily sweep marking past-end agreements EXPIRED (JIKU-43). */
@Component
class AgreementExpiryJob(
    private val agreementService: AgreementService,
) {
    private val log = LoggerFactory.getLogger(AgreementExpiryJob::class.java)

    @Scheduled(cron = "\${admin.agreements.cron:0 0 4 * * *}")
    fun sweep() {
        val expired = agreementService.expireDue()
        if (expired > 0) {
            log.info("Agreement sweep expired {} agreement(s)", expired)
        }
    }
}
