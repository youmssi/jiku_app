package com.jiku.notification.internal

/** A rendered email ready to be delivered by a provider adapter. */
data class EmailMessage(
    val to: String,
    val toName: String,
    val subject: String,
    val htmlBody: String,
)

/**
 * Provider adapter port. The concrete implementation (Resend / Brevo / SendGrid)
 * is chosen later (JIKU-16 interactive step); until then a logging adapter stands
 * in. A real adapter should throw on delivery failure so the caller can retry.
 */
interface EmailSender {
    fun send(
        from: String,
        message: EmailMessage,
    )
}
