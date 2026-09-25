package com.jiku.messaging.internal

import java.util.Base64

/** A rendered email ready to be delivered by a provider adapter. */
data class EmailMessage(
    val to: String,
    val toName: String,
    val subject: String,
    val htmlBody: String,
    val attachments: List<EmailAttachment> = emptyList(),
)

/** A file sent with an email, such as a calendar invite. */
class EmailAttachment(
    val filename: String,
    val contentType: String,
    val content: ByteArray,
) {
    fun base64(): String = Base64.getEncoder().encodeToString(content)
}

/**
 * Provider adapter port. The transport is selected with `jiku.mail.transport`:
 * [LoggingEmailSender] (`log`, default), [SmtpEmailSender] (`smtp`, Mailpit
 * locally), or [ResendEmailSender] (`resend`, production). Adapters throw
 * [EmailDeliveryException] on failure so the orchestration retry engages.
 */
interface EmailSender {
    fun send(
        from: String,
        message: EmailMessage,
    )
}
