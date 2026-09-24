package com.jiku.messaging.internal

import com.jiku.shared.ManualPaymentNotice
import com.jiku.shared.MemberInvitationNotice
import org.springframework.stereotype.Component
import java.util.Locale

/** A rendered email: its subject line and HTML document. */
data class RenderedEmail(
    val subject: String,
    val html: String,
)

/**
 * Renders every email from the shared layout and the language's partials kept in
 * `resources/email-templates/` (see [MessageCatalog]). Client-facing emails
 * (invitation, cancellation) carry the organizer's name, logo and colour, and
 * pass through the per-tenant override resolution (JIKU-91) with fallback to the
 * default document. Platform emails wear Jikū's own mark.
 */
@Component
class EmailTemplateRenderer(
    private val clientTemplates: TenantTemplateResolver,
    private val catalog: MessageCatalog,
) {
    fun renderInvitation(
        email: InvitationEmail,
        language: String,
    ): RenderedEmail {
        val values =
            mapOf(
                "guestName" to email.recipientName,
                "organizerName" to email.organizerName,
                "eventName" to email.eventName,
                "eventWhen" to email.eventWhen.orEmpty(),
                "eventLocation" to email.eventLocation.orEmpty(),
                "primaryColor" to email.primaryColor,
                "invitationUrl" to email.invitationUrl,
            )
        return renderClient(INVITATION, language, values, email.logoUrl, email.organizerName, email.eventWhen, email.eventLocation)
    }

    fun renderCancellation(
        email: CancellationEmail,
        language: String,
    ): RenderedEmail {
        val values =
            mapOf(
                "guestName" to email.recipientName,
                "organizerName" to email.organizerName,
                "eventName" to email.eventName,
                "eventWhen" to email.eventWhen.orEmpty(),
                "eventLocation" to email.eventLocation.orEmpty(),
                "primaryColor" to email.primaryColor,
            )
        return renderClient(CANCELLATION, language, values, email.logoUrl, email.organizerName, email.eventWhen, email.eventLocation)
    }

    /** The build's default document for a client template, as the tenant's editor shows it. */
    fun clientDefault(
        name: String,
        language: String,
    ): String = catalog.emailDocument(language, name, FOOTER_CLIENT, "{{organizerName}}")

    /** A sample `eventDetails` block for the template editor's preview. */
    fun sampleEventDetails(language: String): String = eventDetails(language, "Mardi 3 novembre à 15:00", "Avenue de la République")

    private fun renderClient(
        name: String,
        language: String,
        values: Map<String, String>,
        logoUrl: String?,
        organizerName: String,
        eventWhen: String?,
        eventLocation: String?,
    ): RenderedEmail {
        val html =
            clientTemplates.render(
                name,
                ClientTemplates.CHANNEL_EMAIL,
                clientDefault(name, language),
                values.mapValues { escapeHtml(it.value) } +
                    mapOf(
                        "logoBlock" to logoBlock(logoUrl, organizerName),
                        "eventDetails" to eventDetails(language, eventWhen, eventLocation),
                    ),
            )
        return RenderedEmail(catalog.text(language, "$name.subject", values), html)
    }

    fun renderManualPaymentRequested(
        notice: ManualPaymentNotice,
        language: String,
    ): RenderedEmail =
        renderPlatform(
            "manual-payment-requested",
            language,
            paymentValues(notice),
            mapOf(
                "paymentDetails" to
                    detailsTable(
                        listOf(
                            catalog.text(language, "label.reference") to notice.reference,
                            catalog.text(language, "label.amount") to formatAmount(notice.amountMinor, notice.currency),
                            catalog.text(language, "label.contact") to notice.organizerEmail,
                        ),
                    ),
            ),
        )

    fun renderManualPaymentConfirmed(
        notice: ManualPaymentNotice,
        language: String,
    ): RenderedEmail =
        renderPlatform(
            "manual-payment-confirmed",
            language,
            paymentValues(notice),
            mapOf("paymentDetails" to paymentDetails(notice, language)),
        )

    fun renderManualPaymentRejected(
        notice: ManualPaymentNotice,
        language: String,
    ): RenderedEmail {
        val note = notice.note?.takeIf { it.isNotBlank() }
        val reasonBlock =
            if (note == null) {
                ""
            } else {
                """<p style="margin:0 0 16px;padding:14px 16px;border-radius:12px;""" +
                    """background:#f6f3ee;color:#4a443c;font-size:14px;">""" +
                    """<strong>${escapeHtml(catalog.text(language, "label.reason"))}</strong> · ${escapeHtml(note)}</p>"""
            }
        return renderPlatform(
            "manual-payment-rejected",
            language,
            paymentValues(notice),
            mapOf("paymentDetails" to paymentDetails(notice, language), "reasonBlock" to reasonBlock),
        )
    }

    /** A plain organizer notice (trial, subscription): a heading and one paragraph. */
    fun renderNotice(
        language: String,
        recipientName: String,
        subject: String,
        heading: String,
        body: String,
    ): RenderedEmail =
        RenderedEmail(
            subject,
            renderPlatform("notice", language, mapOf("recipientName" to recipientName, "heading" to heading, "body" to body)).html,
        )

    fun renderPasswordReset(
        actionUrl: String,
        language: String,
    ): RenderedEmail = renderPlatform("password-reset", language, mapOf("actionUrl" to actionUrl))

    fun renderVerifyEmail(
        actionUrl: String,
        language: String,
    ): RenderedEmail = renderPlatform("verify-email", language, mapOf("actionUrl" to actionUrl))

    fun renderMemberInvitation(notice: MemberInvitationNotice): RenderedEmail =
        renderPlatform(
            "member-invitation",
            notice.language,
            mapOf(
                "organizationName" to notice.organizationName,
                "inviterEmail" to notice.inviterEmail,
                "role" to (catalog.textOrNull(notice.language, "role.${notice.role}") ?: notice.role.lowercase()),
                "actionUrl" to notice.actionUrl,
            ),
        )

    /**
     * A platform email: [values] are plain text (escaped here for the HTML, used
     * as-is in the subject); [blocks] are HTML fragments built by this renderer.
     */
    private fun renderPlatform(
        name: String,
        language: String,
        values: Map<String, String>,
        blocks: Map<String, String> = emptyMap(),
    ): RenderedEmail {
        val brand = escapeHtml(catalog.text(language, "brand.platform"))
        val html =
            catalog.substitute(
                catalog.emailDocument(language, name, FOOTER_PLATFORM, brand),
                values.mapValues { escapeHtml(it.value) } +
                    blocks +
                    mapOf("logoBlock" to "", "primaryColor" to PLATFORM_ACCENT),
            )
        return RenderedEmail(catalog.textOrNull(language, "$name.subject", values).orEmpty(), html)
    }

    private fun paymentValues(notice: ManualPaymentNotice): Map<String, String> =
        mapOf(
            "organizerName" to notice.organizerName,
            "tier" to notice.tier,
            "reference" to notice.reference,
        )

    private fun paymentDetails(
        notice: ManualPaymentNotice,
        language: String,
    ): String =
        detailsTable(
            listOf(
                catalog.text(language, "label.tier") to notice.tier,
                catalog.text(language, "label.reference") to notice.reference,
                catalog.text(language, "label.amount") to formatAmount(notice.amountMinor, notice.currency),
            ),
        )

    private fun eventDetails(
        language: String,
        eventWhen: String?,
        eventLocation: String?,
    ): String =
        detailsTable(
            listOfNotNull(
                eventWhen?.let { catalog.text(language, "label.when") to it },
                eventLocation?.let { catalog.text(language, "label.where") to it },
            ),
        )

    /** Label-over-value rows between hairlines, the way a printed programme reads. */
    private fun detailsTable(rows: List<Pair<String, String>>): String {
        if (rows.isEmpty()) {
            return ""
        }
        val cells =
            rows.joinToString("") { (label, value) ->
                """<tr><td style="padding:14px 0;border-bottom:1px solid #ebe5da;">""" +
                    """<span style="display:block;margin:0 0 4px;font:600 11px/1.4 $SANS;""" +
                    """letter-spacing:0.14em;text-transform:uppercase;color:#8c8377;">${escapeHtml(label)}</span>""" +
                    """<span style="font:17px/1.5 Georgia,'Times New Roman',serif;color:#1d1a16;">${escapeHtml(value)}</span></td></tr>"""
            }
        return """<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" """ +
            """style="margin:8px 0 8px;border-top:1px solid #ebe5da;">$cells</table>"""
    }

    private fun logoBlock(
        logoUrl: String?,
        organizerName: String,
    ): String =
        if (!logoUrl.isNullOrBlank()) {
            """<img src="${escapeHtml(logoUrl)}" alt="${escapeHtml(organizerName)}" height="44" """ +
                """style="display:block;height:44px;width:auto;margin:0 0 14px;border:0;" />"""
        } else {
            ""
        }

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
        val grouped = String.format(Locale.ROOT, "%,d", major).replace(',', ' ')
        return "$grouped $currency"
    }

    private companion object {
        const val INVITATION = "invitation"
        const val CANCELLATION = "event-cancelled"
        const val FOOTER_CLIENT = "footer.client"
        const val FOOTER_PLATFORM = "footer.platform"

        /** Jikū's ink: the accent band and buttons of the emails the platform sends in its own name. */
        const val PLATFORM_ACCENT = "#1d1a16"

        const val SANS = "-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"

        val ZERO_DECIMAL_CURRENCIES =
            setOf("BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG", "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF")
    }
}
