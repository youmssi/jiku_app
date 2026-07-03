package com.jiku.notification.internal

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

    private fun load(name: String): String = ClassPathResource("email-templates/$name").inputStream.bufferedReader().use { it.readText() }

    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
