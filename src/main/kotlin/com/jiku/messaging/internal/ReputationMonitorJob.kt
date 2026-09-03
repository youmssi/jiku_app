package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Recomputes the platform-wide sending reputation on a schedule and alerts the
 * team before a threshold breach becomes a deliverability outage. The alert is a
 * structured error log (picked up by monitoring); when an ops address is
 * configured it is also emailed.
 */
@Component
class ReputationMonitorJob(
    private val reputationService: SenderReputationService,
    private val emailSender: EmailSender,
    private val emailProperties: NotificationEmailProperties,
    private val properties: EmailReputationProperties,
) {
    private val log = LoggerFactory.getLogger(ReputationMonitorJob::class.java)

    @Scheduled(cron = "\${notification.reputation.cron:0 0 * * * *}")
    fun evaluate() {
        val snapshot = reputationService.platformReputation()
        if (!snapshot.bounceBreach && !snapshot.complaintBreach) {
            log.debug(
                "Sender reputation healthy: sends={} bounceRate={} complaintRate={}",
                snapshot.sends,
                snapshot.bounceRate,
                snapshot.complaintRate,
            )
            return
        }
        val message =
            "Sender reputation threshold breached over the last window: " +
                "sends=${snapshot.sends}, bounceRate=${"%.3f".format(snapshot.bounceRate)} " +
                "(limit ${properties.bounceRateThreshold}), " +
                "complaintRate=${"%.3f".format(snapshot.complaintRate)} " +
                "(limit ${properties.complaintRateThreshold})"
        log.error("ALERT: {}", message)
        alertOps(message)
    }

    private fun alertOps(message: String) {
        val to = properties.opsAlertEmail?.takeIf { it.isNotBlank() } ?: return
        try {
            emailSender.send(
                emailProperties.from,
                EmailMessage(
                    to = to,
                    toName = "Operations",
                    subject = "Jikū sender reputation alert",
                    htmlBody = "<p>$message</p>",
                ),
            )
        } catch (ex: Exception) {
            log.error("Failed to send reputation alert email", ex)
        }
    }
}
