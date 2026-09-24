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
    private val mailer: OperationalMailer,
    private val salesProperties: NotificationSalesProperties,
) {
    private val log = LoggerFactory.getLogger(OpsAlertListener::class.java)

    @EventListener
    fun onOpsAlert(alert: OpsAlert) {
        log.warn("OPS ALERT: {} — {}", alert.subject, alert.message)
        val to = salesProperties.email.takeIf { it.isNotBlank() } ?: return
        mailer.send("ops-alert", to, "Operations", RenderedEmail(alert.subject, "<p>${escapeHtml(alert.message)}</p>"))
    }
}
