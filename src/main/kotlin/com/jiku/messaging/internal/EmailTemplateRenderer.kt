package com.jiku.messaging.internal

import com.jiku.shared.InvoiceIssuedNotice
import com.jiku.shared.ManualPaymentNotice
import com.jiku.shared.MemberInvitationNotice
import com.jiku.shared.MessageLanguage
import com.jiku.shared.MoneyFormat
import com.jiku.shared.TicketConfirmedNotice
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.pow

/** A rendered email: its subject line and HTML document. */
data class RenderedEmail(
    val subject: String,
    val html: String,
)

/**
 * Renders every email from the shared layout and the language's partials kept in
 * `resources/email-templates/` (see [MessageCatalog]), in the brand's style
 * (JIKU-278): a header in the accent colour with the Leppi watermark and the
 * woven Bande, Inter, and a light and a dark rendering.
 *
 * Client-facing emails (invitation, cancellation, ticket) wear the organizer's
 * colour, name and logo and pass through the per-tenant override resolution
 * (JIKU-91) with fallback to the default document; Jikū only signs the footer.
 * Platform emails wear Jikū's indigo and logo. Text and motifs on the accent turn
 * from white to ink when the colour is too light for white to stay legible.
 */
@Component
class EmailTemplateRenderer(
    private val clientTemplates: TenantTemplateResolver,
    private val catalog: MessageCatalog,
    private val properties: NotificationEmailProperties,
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
        val event = EventMoment(email.eventStart, email.eventTimezone, email.eventWhen, email.eventLocation)
        return renderClient(INVITATION, language, values, email.primaryColor, email.logoUrl, email.organizerName, event)
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
        val event = EventMoment(email.eventStart, email.eventTimezone, email.eventWhen, email.eventLocation)
        return renderClient(CANCELLATION, language, values, email.primaryColor, email.logoUrl, email.organizerName, event)
    }

    /**
     * A confirmed guest's ticket (JIKU-129), in the organizer's colours: the
     * ticket itself with its QR code, the way to open it and, when the event has
     * a date, the calendar links. Not a tenant-editable template.
     */
    fun renderTicketConfirmed(
        notice: TicketConfirmedNotice,
        calendarLink: String?,
    ): RenderedEmail {
        val language = notice.language
        val accent = Accent.of(notice.primaryColor)
        val eventWhen = notice.eventStart?.let { MessageLanguage.formatEventStart(it, notice.eventTimezone, language) }
        val values =
            mapOf(
                "guestName" to notice.recipientName,
                "organizerName" to notice.organizerName,
                "eventName" to notice.eventName,
                "primaryColor" to accent.color,
                "ticketUrl" to notice.ticketUrl,
            )
        val html =
            catalog.substitute(
                catalog.emailDocument(language, TICKET_CONFIRMED, FOOTER_CLIENT, "{{organizerName}}"),
                values.mapValues { escapeHtml(it.value) } +
                    styleValues(accent, clientHeader(language, TICKET_CONFIRMED, notice.organizerName, notice.logoUrl, accent)) +
                    mapOf(
                        "logoBlock" to "",
                        "eventDetails" to "",
                        "ticketCard" to ticketCard(language, notice, eventWhen, accent),
                        "calendarBlock" to calendarLink?.let { calendarBlock(language, it) }.orEmpty(),
                    ),
            )
        return RenderedEmail(catalog.text(language, "$TICKET_CONFIRMED.subject", values), html)
    }

    /** The build's default document for a client template, as the tenant's editor shows it. */
    fun clientDefault(
        name: String,
        language: String,
    ): String = catalog.emailDocument(language, name, FOOTER_CLIENT, "{{organizerName}}")

    /** A sample `eventDetails` block for the template editor's preview. */
    fun sampleEventDetails(language: String): String =
        eventCard(language, EventMoment(null, null, "Mardi 3 novembre à 15:00", "Avenue de la République"), Accent.of(PLATFORM_ACCENT))

    /** The layout values a preview needs besides the template's own variables, for [color]. */
    fun sampleStyleValues(
        language: String,
        color: String,
        organizerName: String,
    ): Map<String, String> {
        val accent = Accent.of(color)
        return styleValues(accent, clientHeader(language, INVITATION, organizerName, null, accent))
    }

    private fun renderClient(
        name: String,
        language: String,
        values: Map<String, String>,
        color: String,
        logoUrl: String?,
        organizerName: String,
        event: EventMoment,
    ): RenderedEmail {
        val accent = Accent.of(color)
        val html =
            clientTemplates.render(
                name,
                ClientTemplates.CHANNEL_EMAIL,
                clientDefault(name, language),
                values.mapValues { escapeHtml(it.value) } +
                    styleValues(accent, clientHeader(language, name, organizerName, logoUrl, accent)) +
                    mapOf(
                        "primaryColor" to accent.color,
                        "logoBlock" to "",
                        "eventDetails" to eventCard(language, event, accent),
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
                """<p class="soft ink" style="margin:0 0 18px;padding:14px 16px;border:1px solid $LINE;border-radius:10px;""" +
                    """background:$OFF;color:#262626;font-size:14px;line-height:1.6;">""" +
                    """<strong>${escapeHtml(catalog.text(language, "label.reason"))}</strong> · ${escapeHtml(note)}</p>"""
            }
        return renderPlatform(
            "manual-payment-rejected",
            language,
            paymentValues(notice),
            mapOf("paymentDetails" to paymentDetails(notice, language), "reasonBlock" to reasonBlock),
        )
    }

    /** An invoice issued when a payment was confirmed (JIKU-277); the PDF travels as an attachment. */
    fun renderInvoiceIssued(
        notice: InvoiceIssuedNotice,
        language: String,
    ): RenderedEmail =
        renderPlatform(
            "invoice-issued",
            language,
            mapOf(
                "recipientName" to notice.recipientName,
                "invoiceNumber" to notice.invoiceNumber,
                "fileName" to notice.fileName,
                "billingUrl" to siteLink(language, BILLING_PATH),
            ),
            mapOf(
                "invoiceDetails" to
                    totalTable(
                        catalog.text(language, "label.totalPaid"),
                        formatAmount(notice.totalMinor, notice.currency),
                        listOf(catalog.text(language, "label.invoice") to notice.invoiceNumber),
                    ),
            ),
        )

    /** A plain organizer notice (trial, subscription): a heading, one paragraph and the way into the app. */
    fun renderNotice(
        language: String,
        recipientName: String,
        subject: String,
        heading: String,
        body: String,
    ): RenderedEmail =
        RenderedEmail(
            subject,
            renderPlatform(
                "notice",
                language,
                mapOf(
                    "recipientName" to recipientName,
                    "heading" to heading,
                    "body" to body,
                    "appUrl" to siteLink(language, DASHBOARD_PATH),
                ),
            ).html,
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
        val accent = Accent.of(PLATFORM_ACCENT)
        val header =
            """<img src="${asset("logo-white.png")}" width="104" height="39" alt="$brand" """ +
                """style="display:block;width:104px;height:39px;border:0;" />"""
        val html =
            catalog.substitute(
                catalog.emailDocument(language, name, FOOTER_PLATFORM, brand),
                values.mapValues { escapeHtml(it.value) } +
                    blocks +
                    styleValues(accent, header) +
                    mapOf("logoBlock" to "", "primaryColor" to accent.color),
            )
        return RenderedEmail(catalog.textOrNull(language, "$name.subject", values).orEmpty(), html)
    }

    /** What the layout and the partials read besides an email's own values. */
    private fun styleValues(
        accent: Accent,
        headerBlock: String,
    ): Map<String, String> =
        mapOf(
            "accent" to accent.color,
            "onAccent" to accent.onAccent,
            "accentText" to accent.text,
            "watermarkUrl" to asset(if (accent.light) "leppi-ink.png" else "leppi-white.png"),
            "bandeUrl" to asset(if (accent.light) "bande-ink.png" else "bande-white.png"),
            "headerBlock" to headerBlock,
            "assetBaseUrl" to properties.assetBaseUrl.trimEnd('/'),
            "siteUrl" to properties.siteUrl.trimEnd('/'),
        )

    /** The organizer's own header: its logo when it has one, what the email is, and its name. */
    private fun clientHeader(
        language: String,
        name: String,
        organizerName: String,
        logoUrl: String?,
        accent: Accent,
    ): String {
        val logo =
            if (logoUrl.isNullOrBlank()) {
                ""
            } else {
                """<img src="${escapeHtml(logoUrl)}" alt="" height="40" """ +
                    """style="display:block;height:40px;width:auto;margin:0 0 16px;padding:6px;border:0;border-radius:8px;background:#FFFFFF;" />"""
            }
        val kicker = catalog.textOrNull(language, "$name.kicker")?.let { escapeHtml(it) }.orEmpty()
        return logo +
            """<p style="margin:0;font:600 12px/1.4 $SANS;letter-spacing:0.14em;text-transform:uppercase;""" +
            """color:${accent.onAccentSoft};">$kicker</p>""" +
            """<p style="margin:6px 0 0;font:700 22px/1.25 $SANS;letter-spacing:-0.01em;color:${accent.onAccent};">${escapeHtml(
                organizerName,
            )}</p>"""
    }

    /**
     * When and where: a date tile beside the time and place when the start is
     * known, the plain rows otherwise; nothing when there is neither.
     */
    private fun eventCard(
        language: String,
        event: EventMoment,
        accent: Accent,
    ): String {
        val zone = event.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        if (event.start != null && zone != null) {
            val month = escapeHtml(catalog.formatDate(language, "date.tile.month", event.start, zone).trimEnd('.'))
            val day = escapeHtml(catalog.formatDate(language, "date.tile.day", event.start, zone))
            val weekday = escapeHtml(catalog.formatDate(language, "date.tile.weekday", event.start, zone))
            val rows =
                listOfNotNull(
                    catalog.text(language, "label.time") to catalog.formatDate(language, "date.tile.time", event.start, zone),
                    event.location?.takeIf { it.isNotBlank() }?.let { catalog.text(language, "label.where") to it },
                )
            return """<table role="presentation" class="line" width="100%" cellpadding="0" cellspacing="0" border="0" """ +
                """style="margin:24px 0 4px;border:1px solid $LINE;border-radius:10px;border-collapse:separate;"><tr>""" +
                """<td class="line" width="92" align="center" valign="middle" style="padding:18px 0;border-right:1px solid $LINE;">""" +
                """<p class="accent-text" style="margin:0;font:600 12px/1 $SANS;letter-spacing:0.12em;text-transform:uppercase;""" +
                """color:${accent.text};">$month</p>""" +
                """<p class="ink" style="margin:6px 0 4px;font:700 34px/1 $SANS;letter-spacing:-0.03em;color:$INK;">$day</p>""" +
                """<p class="muted" style="margin:0;font:500 12px/1 $SANS;color:$STONE;">$weekday</p></td>""" +
                """<td valign="middle" style="padding:14px 20px;">${rowsHtml(rows)}</td></tr></table>"""
        }
        val rows =
            listOfNotNull(
                event.label?.takeIf { it.isNotBlank() }?.let { catalog.text(language, "label.when") to it },
                event.location?.takeIf { it.isNotBlank() }?.let { catalog.text(language, "label.where") to it },
            )
        if (rows.isEmpty()) return ""
        return """<table role="presentation" class="line" width="100%" cellpadding="0" cellspacing="0" border="0" """ +
            """style="margin:24px 0 4px;border:1px solid $LINE;border-radius:10px;border-collapse:separate;"><tr>""" +
            """<td style="padding:14px 20px;">${rowsHtml(rows)}</td></tr></table>"""
    }

    private fun rowsHtml(rows: List<Pair<String, String>>): String =
        rows.joinToString("") { (label, value) ->
            """<p class="ink" style="margin:4px 0;font:400 15px/1.5 $SANS;color:$INK;">""" +
                """<span class="muted" style="color:$STONE;">${escapeHtml(label)}</span>&nbsp;&nbsp;""" +
                """<strong style="font-weight:600;">${escapeHtml(value)}</strong></p>"""
        }

    /** The ticket as a card: who, which category, when and where, then its QR code and short code. */
    private fun ticketCard(
        language: String,
        notice: TicketConfirmedNotice,
        eventWhen: String?,
        accent: Accent,
    ): String {
        val cells =
            listOfNotNull(
                catalog.text(language, "label.guest") to notice.recipientName,
                notice.categoryName?.let { catalog.text(language, "label.category") to it },
                eventWhen?.let { catalog.text(language, "label.when") to it },
                notice.eventLocation?.let { catalog.text(language, "label.where") to it },
            ).filter { it.second.isNotBlank() }
        val grid =
            cells.chunked(2).joinToString("") { pair ->
                "<tr>" +
                    pair.joinToString("") { (label, value) ->
                        """<td width="50%" valign="top" style="padding:8px 8px 8px 0;">""" +
                            """<p class="muted" style="margin:0 0 2px;font:400 12px/1.4 $SANS;color:$STONE;">${escapeHtml(label)}</p>""" +
                            """<p class="ink" style="margin:0;font:600 15px/1.4 $SANS;color:$INK;">${escapeHtml(value)}</p></td>"""
                    } +
                    (if (pair.size == 1) "<td></td>" else "") +
                    "</tr>"
            }
        val code = ticketCode(notice.ticketUrl)
        val qr =
            notice.qrImageUrl
                ?.let { url ->
                    """<tr><td class="line" align="center" style="padding:22px 22px 24px;border-top:2px dashed $LINE;">""" +
                        """<img src="${escapeHtml(
                            url,
                        )}" width="168" height="168" alt="${escapeHtml(catalog.text(language, "ticket.qrAlt"))}" """ +
                        """style="display:block;width:168px;height:168px;border:0;padding:10px;""" +
                        """border-radius:8px;background:#FFFFFF;" />""" +
                        (
                            code?.let {
                                """<p class="ink" style="margin:12px 0 0;font:600 15px/1 $SANS;letter-spacing:0.18em;color:$INK;">${escapeHtml(
                                    it,
                                )}</p>"""
                            } ?: ""
                        ) + "</td></tr>"
                }.orEmpty()
        return """<table role="presentation" class="line" width="100%" cellpadding="0" cellspacing="0" border="0" """ +
            """style="margin:22px 0 4px;border:1px solid $LINE;border-radius:12px;border-collapse:separate;">""" +
            """<tr><td style="padding:18px 20px 12px;">""" +
            """<p class="accent-text" style="margin:0 0 6px;font:600 12px/1.4 $SANS;letter-spacing:0.12em;""" +
            """text-transform:uppercase;color:${accent.text};">""" +
            """${escapeHtml(notice.eventName)}</p>""" +
            """<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">$grid</table></td></tr>$qr</table>"""
    }

    private fun calendarBlock(
        language: String,
        link: String,
    ): String {
        val label = escapeHtml(catalog.text(language, "calendar.google"))
        val note = escapeHtml(catalog.text(language, "calendar.file"))
        return """<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:0 0 10px;"><tr>""" +
            """<td class="line" style="border-radius:8px;border:1px solid #D4D4D4;"><a class="ink" href="${escapeHtml(link)}" """ +
            """style="display:inline-block;padding:13px 22px;border-radius:8px;font:600 14px/1 $SANS;color:$INK;""" +
            """text-decoration:none;">$label</a></td></tr></table>""" +
            """<p class="muted" style="margin:0;font-size:13px;line-height:1.6;color:$STONE;">$note</p>"""
    }

    private fun paymentValues(notice: ManualPaymentNotice): Map<String, String> =
        mapOf(
            "organizerName" to notice.organizerName,
            "guests" to notice.guests.toString(),
            "reference" to notice.reference,
        )

    private fun paymentDetails(
        notice: ManualPaymentNotice,
        language: String,
    ): String =
        detailsTable(
            listOf(
                catalog.text(language, "label.guests") to notice.guests.toString(),
                catalog.text(language, "label.reference") to notice.reference,
                catalog.text(language, "label.amount") to formatAmount(notice.amountMinor, notice.currency),
            ),
        )

    /** Label and value rows in a soft box, the way a receipt reads. */
    private fun detailsTable(rows: List<Pair<String, String>>): String {
        if (rows.isEmpty()) return ""
        val cells =
            rows.withIndex().joinToString("") { (index, row) ->
                val rule = if (index == 0) "" else "border-top:1px solid $LINE;"
                """<tr><td class="muted line" style="padding:12px 18px;$rule font:400 14px/1.5 $SANS;color:$STONE;">${escapeHtml(
                    row.first,
                )}</td>""" +
                    """<td class="ink line" align="right" style="padding:12px 18px;$rule font:600 15px/1.5 $SANS;color:$INK;">""" +
                    """${escapeHtml(row.second)}</td></tr>"""
            }
        return """<table role="presentation" class="soft" width="100%" cellpadding="0" cellspacing="0" border="0" """ +
            """style="margin:22px 0 22px;background:$OFF;border:1px solid $LINE;border-radius:10px;border-collapse:separate;">$cells</table>"""
    }

    /** A receipt total: the detail rows, then the amount paid set large. */
    private fun totalTable(
        totalLabel: String,
        total: String,
        rows: List<Pair<String, String>>,
    ): String {
        val details =
            rows.joinToString("") { (label, value) ->
                """<tr><td class="muted" style="padding:14px 18px 0;font:400 14px/1.5 $SANS;color:$STONE;">${escapeHtml(label)}</td>""" +
                    """<td class="ink" align="right" style="padding:14px 18px 0;font:500 14px/1.5 $SANS;color:$INK;">${escapeHtml(
                        value,
                    )}</td></tr>"""
            }
        return """<table role="presentation" class="soft" width="100%" cellpadding="0" cellspacing="0" border="0" """ +
            """style="margin:22px 0 16px;background:$OFF;border:1px solid $LINE;border-radius:10px;border-collapse:separate;">$details""" +
            """<tr><td class="ink" style="padding:14px 18px 16px;font:600 15px/1.4 $SANS;color:$INK;">${escapeHtml(totalLabel)}</td>""" +
            """<td class="ink" align="right" style="padding:14px 18px 16px;font:700 22px/1.2 $SANS;letter-spacing:-0.02em;color:$INK;">""" +
            """${escapeHtml(total)}</td></tr></table>"""
    }

    private fun asset(file: String): String = "${properties.assetBaseUrl.trimEnd('/')}/$file"

    /** A page of the web app in [language]: French is unprefixed, English lives under `/en`. */
    private fun siteLink(
        language: String,
        path: String,
    ): String {
        val prefix = if (MessageLanguage.normalize(language) == MessageLanguage.FRENCH) "" else "/${MessageLanguage.normalize(language)}"
        return properties.siteUrl.trimEnd('/') + prefix + path
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
        val major = if (MoneyFormat.isZeroDecimal(currency)) amountMinor else amountMinor / 100
        val grouped = String.format(Locale.ROOT, "%,d", major).replace(',', ' ')
        return "$grouped $currency"
    }

    /** When and where an event happens, structured when the start is known. */
    private data class EventMoment(
        val start: Instant?,
        val timezone: String?,
        val label: String?,
        val location: String?,
    )

    /**
     * An accent colour and what stays legible on it: white text and motifs on a
     * dark colour, ink on a light one (WCAG contrast of 4.5:1). [text] is the
     * colour that accent may take as text on white.
     */
    private class Accent private constructor(
        val color: String,
    ) {
        private val whiteContrast = contrastWithWhite(color)
        val light: Boolean = whiteContrast < MIN_CONTRAST
        val onAccent: String = if (light) INK else "#FFFFFF"
        val onAccentSoft: String = if (light) "rgba(10,10,10,0.72)" else "rgba(255,255,255,0.78)"
        val text: String = if (light) INK else color

        companion object {
            private val HEX = Regex("^#[0-9a-fA-F]{6}$")

            fun of(color: String?): Accent = Accent(color?.trim()?.takeIf { HEX.matches(it) }?.uppercase() ?: PLATFORM_ACCENT)

            private fun contrastWithWhite(hex: String): Double {
                val luminance =
                    (1..5 step 2)
                        .map { hex.substring(it, it + 2).toInt(16) / 255.0 }
                        .map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
                        .let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }
                return 1.05 / (luminance + 0.05)
            }
        }
    }

    private companion object {
        const val INVITATION = "invitation"
        const val CANCELLATION = "event-cancelled"
        const val TICKET_CONFIRMED = "ticket-confirmed"
        const val FOOTER_CLIENT = "footer.client"
        const val FOOTER_PLATFORM = "footer.platform"
        const val BILLING_PATH = "/billing"
        const val DASHBOARD_PATH = "/dashboard"

        /** Jikū's indigo night: the header and buttons of the emails the platform sends in its own name. */
        const val PLATFORM_ACCENT = "#0F172A"
        const val MIN_CONTRAST = 4.5

        const val INK = "#0A0A0A"
        const val STONE = "#737373"
        const val LINE = "#E5E5E5"
        const val OFF = "#FAFAFA"
        const val SANS = "Inter,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"

        /** The short code at the end of a ticket link (`…/v/<code>/ticket`), shown under the QR code. */
        fun ticketCode(ticketUrl: String): String? =
            ticketUrl
                .substringAfter("/v/", "")
                .substringBefore('/')
                .takeIf { it.length in 6..16 && it.all(Char::isLetterOrDigit) }
                ?.uppercase()
                ?.let { if (it.length == 8) "${it.take(4)}-${it.drop(4)}" else it }
    }
}
