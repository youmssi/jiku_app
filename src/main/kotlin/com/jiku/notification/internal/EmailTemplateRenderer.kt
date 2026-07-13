package com.jiku.notification.internal

import com.jiku.shared.ManualPaymentNotice
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Renders HTML emails from templates kept in `resources/email-templates/`. Uses
 * simple `{{placeholder}}` substitution with HTML-escaped values; templates are
 * loaded once and cached.
 */
@Component
class EmailTemplateRenderer {
    private val invitationTemplate: String by lazy { load("invitation.html") }
    private val cancellationTemplate: String by lazy { load("event-cancelled.html") }
    private val manualRequestedTemplate: String by lazy { load("manual-payment-requested.html") }
    private val manualConfirmedTemplate: String by lazy { load("manual-payment-confirmed.html") }
    private val manualRejectedTemplate: String by lazy { load("manual-payment-rejected.html") }
    private val trialNoticeTemplate: String by lazy { load("trial-notice.html") }
    private val passwordResetTemplate: String by lazy { load("password-reset.html") }
    private val verifyEmailTemplate: String by lazy { load("verify-email.html") }

    fun renderInvitation(email: InvitationEmail): String {
        val logoBlock =
            if (!email.logoUrl.isNullOrBlank()) {
                """<img src="${escape(email.logoUrl)}" alt="${escape(email.organizerName)}" """ +
                    """height="48" style="margin-bottom:16px;" />"""
            } else {
                ""
            }
        val details =
            buildString {
                email.eventWhen?.let { append("""<p style="margin:0 0 4px;color:#555;">📅 ${escape(it)}</p>""") }
                email.eventLocation?.let { append("""<p style="margin:0 0 4px;color:#555;">📍 ${escape(it)}</p>""") }
            }
        return invitationTemplate
            .replace("{{logoBlock}}", logoBlock)
            .replace("{{organizerName}}", escape(email.organizerName))
            .replace("{{guestName}}", escape(email.recipientName))
            .replace("{{eventName}}", escape(email.eventName))
            .replace("{{eventDetails}}", details)
            .replace("{{primaryColor}}", escape(email.primaryColor))
            .replace("{{invitationUrl}}", escape(email.invitationUrl))
    }

    fun renderCancellation(email: CancellationEmail): String {
        val logoBlock =
            if (!email.logoUrl.isNullOrBlank()) {
                """<img src="${escape(email.logoUrl)}" alt="${escape(email.organizerName)}" """ +
                    """height="48" style="margin-bottom:16px;" />"""
            } else {
                ""
            }
        val details =
            buildString {
                email.eventWhen?.let { append("""<p style="margin:0 0 4px;color:#555;">📅 ${escape(it)}</p>""") }
                email.eventLocation?.let { append("""<p style="margin:0 0 4px;color:#555;">📍 ${escape(it)}</p>""") }
            }
        return cancellationTemplate
            .replace("{{logoBlock}}", logoBlock)
            .replace("{{organizerName}}", escape(email.organizerName))
            .replace("{{guestName}}", escape(email.recipientName))
            .replace("{{eventName}}", escape(email.eventName))
            .replace("{{eventDetails}}", details)
    }

    fun renderManualPaymentRequested(notice: ManualPaymentNotice): String =
        manualRequestedTemplate
            .replace("{{organizerName}}", escape(notice.organizerName))
            .replace("{{organizerEmail}}", escape(notice.organizerEmail))
            .replace("{{tier}}", escape(notice.tier))
            .replace("{{reference}}", escape(notice.reference))
            .replace("{{amount}}", formatAmount(notice.amountMinor, notice.currency))
            .replace("{{paymentId}}", notice.paymentId.toString())

    fun renderManualPaymentConfirmed(notice: ManualPaymentNotice): String =
        manualConfirmedTemplate
            .replace("{{organizerName}}", escape(notice.organizerName))
            .replace("{{tier}}", escape(notice.tier))
            .replace("{{reference}}", escape(notice.reference))
            .replace("{{amount}}", formatAmount(notice.amountMinor, notice.currency))

    fun renderManualPaymentRejected(notice: ManualPaymentNotice): String {
        val reasonBlock =
            notice.note?.takeIf { it.isNotBlank() }?.let {
                """<p style="margin:0 0 4px;color:#555;">Reason: ${escape(it)}</p>"""
            } ?: ""
        return manualRejectedTemplate
            .replace("{{organizerName}}", escape(notice.organizerName))
            .replace("{{tier}}", escape(notice.tier))
            .replace("{{reference}}", escape(notice.reference))
            .replace("{{reasonBlock}}", reasonBlock)
    }

    fun renderTrialNotice(
        organizerName: String,
        heading: String,
        body: String,
    ): String =
        trialNoticeTemplate
            .replace("{{organizerName}}", escape(organizerName))
            .replace("{{heading}}", escape(heading))
            .replace("{{body}}", escape(body))

    fun renderPasswordReset(actionUrl: String): String = passwordResetTemplate.replace("{{actionUrl}}", escape(actionUrl))

    fun renderVerifyEmail(actionUrl: String): String = verifyEmailTemplate.replace("{{actionUrl}}", escape(actionUrl))

    /** Minor units to a display amount (e.g. 500000 XOF-minor → "5 000 XOF"). */
    private fun formatAmount(
        amountMinor: Long,
        currency: String,
    ): String {
        val major = amountMinor / 100
        val grouped = "%,d".format(major).replace(',', ' ')
        return "$grouped $currency"
    }

    private fun load(name: String): String = ClassPathResource("email-templates/$name").inputStream.bufferedReader().use { it.readText() }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
