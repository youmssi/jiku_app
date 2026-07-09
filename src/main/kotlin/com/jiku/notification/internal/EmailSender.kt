package com.jiku.notification.internal

/** A rendered email ready to be delivered by a provider adapter. */
data class EmailMessage(
    val to: String,
    val toName: String,
    val subject: String,
    val htmlBody: String,
)

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
