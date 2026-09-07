package com.jiku.messaging.internal

import com.jiku.shared.BookingNotice
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
    private val bookingPaymentDeclaredTemplate: String by lazy { load("booking-payment-declared.html") }
    private val bookingDuplicateReferenceTemplate: String by lazy { load("booking-duplicate-reference.html") }
    private val bookingDepositVerifiedTemplate: String by lazy { load("booking-deposit-verified.html") }
    private val bookingBalanceVerifiedTemplate: String by lazy { load("booking-balance-verified.html") }
    private val bookingPaymentRejectedTemplate: String by lazy { load("booking-payment-rejected.html") }
    private val bookingRefundedTemplate: String by lazy { load("booking-refunded.html") }

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
            "organizerName" to escape(email.organizerName),
            "guestName" to escape(email.recipientName),
            "eventName" to escape(email.eventName),
            "eventWhen" to email.eventWhen?.let { escape(it) }.orEmpty(),
            "eventLocation" to email.eventLocation?.let { escape(it) }.orEmpty(),
            "eventDetails" to details(email.eventWhen, email.eventLocation),
            "primaryColor" to escape(email.primaryColor),
            "invitationUrl" to escape(email.invitationUrl),
        )

    private fun cancellationValues(email: CancellationEmail): Map<String, String> =
        mapOf(
            "logoBlock" to logoBlock(email.logoUrl, email.organizerName),
            "organizerName" to escape(email.organizerName),
            "guestName" to escape(email.recipientName),
            "eventName" to escape(email.eventName),
            "eventWhen" to email.eventWhen?.let { escape(it) }.orEmpty(),
            "eventLocation" to email.eventLocation?.let { escape(it) }.orEmpty(),
            "eventDetails" to details(email.eventWhen, email.eventLocation),
        )

    private fun logoBlock(
        logoUrl: String?,
        organizerName: String,
    ): String =
        if (!logoUrl.isNullOrBlank()) {
            """<img src="${escape(logoUrl)}" alt="${escape(organizerName)}" """ +
                """height="48" style="margin-bottom:16px;" />"""
        } else {
            ""
        }

    private fun details(
        eventWhen: String?,
        eventLocation: String?,
    ): String =
        buildString {
            eventWhen?.let { append("""<p style="margin:0 0 4px;color:#555;">📅 ${escape(it)}</p>""") }
            eventLocation?.let { append("""<p style="margin:0 0 4px;color:#555;">📍 ${escape(it)}</p>""") }
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

    fun renderMemberInvitation(notice: MemberInvitationNotice): String =
        memberInvitationTemplate
            .replace("{{organizationName}}", escape(notice.organizationName))
            .replace("{{inviterEmail}}", escape(notice.inviterEmail))
            .replace("{{role}}", escape(notice.role))
            .replace("{{actionUrl}}", escape(notice.actionUrl))

    fun renderBookingPaymentDeclared(notice: BookingNotice): String =
        bookingPaymentDeclaredTemplate
            .replace("{{customerName}}", escape(notice.customerName))
            .replace("{{customerEmail}}", escape(notice.customerEmail))
            .replace("{{customerPhone}}", escape(notice.customerPhone))
            .replace("{{kind}}", escape(notice.declarationKind.orEmpty()))
            .replace("{{reference}}", escape(notice.reference.orEmpty()))
            .replace("{{amount}}", formatAmount(notice.amountMinor ?: 0, notice.currency))
            .replace("{{bookingId}}", notice.bookingId.toString())

    fun renderBookingDuplicateReference(notice: BookingNotice): String =
        bookingDuplicateReferenceTemplate
            .replace("{{customerName}}", escape(notice.customerName))
            .replace("{{customerEmail}}", escape(notice.customerEmail))
            .replace("{{customerPhone}}", escape(notice.customerPhone))
            .replace("{{reference}}", escape(notice.reference.orEmpty()))
            .replace("{{amount}}", formatAmount(notice.amountMinor ?: 0, notice.currency))

    fun renderBookingDepositVerified(notice: BookingNotice): String =
        bookingDepositVerifiedTemplate
            .replace("{{customerName}}", escape(notice.customerName))
            .replace("{{amount}}", formatAmount(notice.amountMinor ?: 0, notice.currency))
            .replace("{{reference}}", escape(notice.reference.orEmpty()))
            .replace("{{balanceAmount}}", formatAmount(notice.balanceAmountMinor ?: 0, notice.currency))
            .replace("{{balanceDueDate}}", escape(notice.balanceDueDate.orEmpty()))

    fun renderBookingBalanceVerified(notice: BookingNotice): String =
        bookingBalanceVerifiedTemplate
            .replace("{{customerName}}", escape(notice.customerName))
            .replace("{{amount}}", formatAmount(notice.amountMinor ?: 0, notice.currency))
            .replace("{{reference}}", escape(notice.reference.orEmpty()))

    fun renderBookingPaymentRejected(notice: BookingNotice): String {
        val reasonBlock =
            notice.note?.takeIf { it.isNotBlank() }?.let {
                """<p style="margin:0 0 4px;color:#555555;">Reason: ${escape(it)}</p>"""
            } ?: ""
        return bookingPaymentRejectedTemplate
            .replace("{{customerName}}", escape(notice.customerName))
            .replace("{{reference}}", escape(notice.reference.orEmpty()))
            .replace("{{reasonBlock}}", reasonBlock)
    }

    fun renderBookingRefunded(notice: BookingNotice): String =
        bookingRefundedTemplate
            .replace("{{customerName}}", escape(notice.customerName))
            .replace("{{amount}}", formatAmount(notice.amountMinor ?: 0, notice.currency))
            .replace("{{reference}}", escape(notice.reference.orEmpty()))
            .replace("{{bookingId}}", notice.bookingId.toString())

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

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
