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
    private val emailProperties: NotificationEmailProperties,
    private val catalog: MessageCatalog,
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
        mailer.send(
            "manual-payment",
            to,
            "Sales",
            templateRenderer.renderManualPaymentRequested(notice, emailProperties.platformLanguage),
        )
    }

    private fun notifyOrganizer(notice: ManualPaymentNotice) {
        val to = notice.organizerEmail.takeIf { it.isNotBlank() }
        if (to == null) {
            log.warn("Manual payment {} has no organizer email to notify", notice.reference)
            return
        }
        val language = catalog.language(notice.tenantId)
        val email =
            if (notice.kind == ManualPaymentNotice.KIND_CONFIRMED) {
                templateRenderer.renderManualPaymentConfirmed(notice, language)
            } else {
                templateRenderer.renderManualPaymentRejected(notice, language)
            }
        mailer.send("manual-payment", to, notice.organizerName, email)
    }
}

/** Sales mailbox for activation requests; blank disables the sales email. */
@ConfigurationProperties(prefix = "notification.sales")
data class NotificationSalesProperties(
    val email: String = "",
)
