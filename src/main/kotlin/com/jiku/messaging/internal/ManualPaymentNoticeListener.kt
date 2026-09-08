package com.jiku.messaging.internal

import com.jiku.shared.ManualPaymentNotice
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Emails for the manual payment flow (JIKU-41): a new activation request goes to
 * the sales mailbox; a confirmation or rejection goes to the organizer. Content
 * only — the delivery mechanics live in [OperationalMailer]; the admin audit log
 * already records the action.
 */
@Component
class ManualPaymentNoticeListener(
    private val mailer: OperationalMailer,
    private val salesProperties: NotificationSalesProperties,
    private val templateRenderer: EmailTemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(ManualPaymentNoticeListener::class.java)

    @EventListener
    fun onManualPaymentNotice(notice: ManualPaymentNotice) {
        when (notice.kind) {
            ManualPaymentNotice.KIND_REQUESTED -> notifySales(notice)
            ManualPaymentNotice.KIND_CONFIRMED, ManualPaymentNotice.KIND_REJECTED -> notifyOrganizer(notice)
            else -> log.warn("Ignoring manual payment notice of unknown kind: {}", notice.kind)
        }
    }

    private fun notifySales(notice: ManualPaymentNotice) {
        val to = salesProperties.email.takeIf { it.isNotBlank() }
        if (to == null) {
            log.warn(
                "No sales mailbox configured (NOTIFICATION_SALES_EMAIL) — activation request {} only visible in the admin payments desk",
                notice.reference,
            )
            return
        }
        mailer.sendOperationalHtml(
            label = "manual-payment",
            to = to,
            toName = "Sales",
            subject = "New activation request ${notice.reference} — ${notice.organizerName}",
            htmlBody = templateRenderer.renderManualPaymentRequested(notice),
        )
    }

    private fun notifyOrganizer(notice: ManualPaymentNotice) {
        val to = notice.organizerEmail.takeIf { it.isNotBlank() }
        if (to == null) {
            log.warn("Manual payment {} has no organizer email to notify", notice.reference)
            return
        }
        val confirmed = notice.kind == ManualPaymentNotice.KIND_CONFIRMED
        mailer.sendOperationalHtml(
            label = "manual-payment",
            to = to,
            toName = notice.organizerName,
            subject =
                if (confirmed) {
                    "Your ${notice.tier} activation is confirmed"
                } else {
                    "About your ${notice.tier} activation request"
                },
            htmlBody =
                if (confirmed) {
                    templateRenderer.renderManualPaymentConfirmed(notice)
                } else {
                    templateRenderer.renderManualPaymentRejected(notice)
                },
        )
    }
}

/** Sales mailbox for activation requests; blank disables the sales email. */
@ConfigurationProperties(prefix = "notification.sales")
data class NotificationSalesProperties(
    val email: String = "",
)
