package com.jiku.messaging.internal

import com.jiku.shared.OpsAlert
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Delivers [OpsAlert]s to the configured sales/ops mailbox (JIKU-43). The alert
 * is always logged at error-free WARN visibility, so an unconfigured mailbox
 * still leaves a trace monitoring can pick up.
 */
@Component
class OpsAlertListener(
    private val emailSender: EmailSender,
    private val emailProperties: NotificationEmailProperties,
    private val salesProperties: NotificationSalesProperties,
) {
    private val log = LoggerFactory.getLogger(OpsAlertListener::class.java)

    @EventListener
    fun onOpsAlert(alert: OpsAlert) {
        log.warn("OPS ALERT: {} — {}", alert.subject, alert.message)
        val to = salesProperties.email.takeIf { it.isNotBlank() } ?: return
        try {
            emailSender.send(
                emailProperties.from,
                EmailMessage(
                    to = to,
                    toName = "Operations",
                    subject = alert.subject,
                    htmlBody = "<p>${escapeHtml(alert.message)}</p>",
                ),
            )
        } catch (ex: Exception) {
            log.error("Failed to send ops alert email", ex)
        }
    }

}
