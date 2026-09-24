package com.jiku.messaging.internal

import com.jiku.shared.ManualPaymentNotice
import com.jiku.shared.MemberInvitationNotice
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Renders HTML emails from templates kept in `resources/email-templates/`. Uses
 * simple `{{placeholder}}` substitution with HTML-escaped values; templates are
 * loaded once and cached. Client-facing templates (invitation, cancellation) pass
 * through the per-tenant override resolution (JIKU-91) with fallback to the
 * build's default.
 */
@Component
class EmailTemplateRenderer(
    private val clientTemplates: TenantTemplateResolver,
) {
    private val invitationTemplate: String by lazy { load("invitation.html") }
    private val cancellationTemplate: String by lazy { load("event-cancelled.html") }
    private val manualRequestedTemplate: String by lazy { load("manual-payment-requested.html") }
    private val manualConfirmedTemplate: String by lazy { load("manual-payment-confirmed.html") }
    private val manualRejectedTemplate: String by lazy { load("manual-payment-rejected.html") }
    private val trialNoticeTemplate: String by lazy { load("trial-notice.html") }
    private val passwordResetTemplate: String by lazy { load("password-reset.html") }
    private val verifyEmailTemplate: String by lazy { load("verify-email.html") }
    private val memberInvitationTemplate: String by lazy { load("member-invitation.html") }

    fun renderInvitation(email: InvitationEmail): String =
        clientTemplates.render(
            ClientTemplates.definition("invitation").let { requireNotNull(it) }.name,
            ClientTemplates.CHANNEL_EMAIL,
            invitationTemplate,
            invitationValues(email),
        )

    fun renderCancellation(email: CancellationEmail): String =
        clientTemplates.render(
            ClientTemplates.definition("event-cancelled").let { requireNotNull(it) }.name,
            ClientTemplates.CHANNEL_EMAIL,
            cancellationTemplate,
            cancellationValues(email),
        )

    private fun invitationValues(email: InvitationEmail): Map<String, String> =
        mapOf(
            "logoBlock" to logoBlock(email.logoUrl, email.organizerName),
            "organizerName" to escapeHtml(email.organizerName),
            "guestName" to escapeHtml(email.recipientName),
            "eventName" to escapeHtml(email.eventName),
            "eventWhen" to email.eventWhen?.let { escapeHtml(it) }.orEmpty(),
            "eventLocation" to email.eventLocation?.let { escapeHtml(it) }.orEmpty(),
            "eventDetails" to details(email.eventWhen, email.eventLocation),
            "primaryColor" to escapeHtml(email.primaryColor),
            "invitationUrl" to escapeHtml(email.invitationUrl),
        )

    private fun cancellationValues(email: CancellationEmail): Map<String, String> =
        mapOf(
            "logoBlock" to logoBlock(email.logoUrl, email.organizerName),
            "organizerName" to escapeHtml(email.organizerName),
            "guestName" to escapeHtml(email.recipientName),
            "eventName" to escapeHtml(email.eventName),
            "eventWhen" to email.eventWhen?.let { escapeHtml(it) }.orEmpty(),
            "eventLocation" to email.eventLocation?.let { escapeHtml(it) }.orEmpty(),
            "eventDetails" to details(email.eventWhen, email.eventLocation),
        )

    private fun logoBlock(
        logoUrl: String?,
        organizerName: String,
    ): String =
        if (!logoUrl.isNullOrBlank()) {
            """<img src="${escapeHtml(logoUrl)}" alt="${escapeHtml(organizerName)}" """ +
                """height="48" style="margin-bottom:16px;" />"""
        } else {
            ""
        }

    private fun details(
        eventWhen: String?,
        eventLocation: String?,
    ): String =
        buildString {
            eventWhen?.let { append("""<p style="margin:0 0 4px;color:#555;">📅 ${escapeHtml(it)}</p>""") }
            eventLocation?.let { append("""<p style="margin:0 0 4px;color:#555;">📍 ${escapeHtml(it)}</p>""") }
        }

    fun renderManualPaymentRequested(notice: ManualPaymentNotice): String =
        manualRequestedTemplate
            .replace("{{organizerName}}", escapeHtml(notice.organizerName))
            .replace("{{organizerEmail}}", escapeHtml(notice.organizerEmail))
            .replace("{{tier}}", escapeHtml(notice.tier))
            .replace("{{reference}}", escapeHtml(notice.reference))
            .replace("{{amount}}", formatAmount(notice.amountMinor, notice.currency))
            .replace("{{paymentId}}", notice.paymentId.toString())

    fun renderManualPaymentConfirmed(notice: ManualPaymentNotice): String =
        manualConfirmedTemplate
            .replace("{{organizerName}}", escapeHtml(notice.organizerName))
            .replace("{{tier}}", escapeHtml(notice.tier))
            .replace("{{reference}}", escapeHtml(notice.reference))
            .replace("{{amount}}", formatAmount(notice.amountMinor, notice.currency))

    fun renderManualPaymentRejected(notice: ManualPaymentNotice): String {
        val reasonBlock =
            notice.note?.takeIf { it.isNotBlank() }?.let {
                """<p style="margin:0 0 4px;color:#555;">Reason: ${escapeHtml(it)}</p>"""
            } ?: ""
        return manualRejectedTemplate
            .replace("{{organizerName}}", escapeHtml(notice.organizerName))
            .replace("{{tier}}", escapeHtml(notice.tier))
            .replace("{{reference}}", escapeHtml(notice.reference))
            .replace("{{reasonBlock}}", reasonBlock)
    }

    fun renderTrialNotice(
        organizerName: String,
        heading: String,
        body: String,
    ): String =
        trialNoticeTemplate
            .replace("{{organizerName}}", escapeHtml(organizerName))
            .replace("{{heading}}", escapeHtml(heading))
            .replace("{{body}}", escapeHtml(body))

    fun renderPasswordReset(actionUrl: String): String = passwordResetTemplate.replace("{{actionUrl}}", escapeHtml(actionUrl))

    fun renderVerifyEmail(actionUrl: String): String = verifyEmailTemplate.replace("{{actionUrl}}", escapeHtml(actionUrl))

    fun renderMemberInvitation(notice: MemberInvitationNotice): String =
        memberInvitationTemplate
            .replace("{{organizationName}}", escapeHtml(notice.organizationName))
            .replace("{{inviterEmail}}", escapeHtml(notice.inviterEmail))
            .replace("{{role}}", escapeHtml(notice.role))
            .replace("{{actionUrl}}", escapeHtml(notice.actionUrl))

    /**
     * Minor units to a display amount (e.g. 150000 GNF-minor → "150 000 GNF").
     * GNF has no minor unit (mirrors BillingHistoryController/lib/currency.ts),
     * so this is the full amount, not centimes.
     */
    private fun formatAmount(
        amountMinor: Long,
        currency: String,
    ): String {
        val major = if (currency.uppercase() in ZERO_DECIMAL_CURRENCIES) amountMinor else amountMinor / 100
        val grouped = "%,d".format(major).replace(',', ' ')
        return "$grouped $currency"
    }

    private companion object {
        val ZERO_DECIMAL_CURRENCIES =
            setOf("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF")
    }

    private fun load(name: String): String = ClassPathResource("email-templates/$name").inputStream.bufferedReader().use { it.readText() }
}
