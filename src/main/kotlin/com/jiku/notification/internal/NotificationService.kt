package com.jiku.notification.internal

import com.jiku.notification.InvitationEmail
import com.jiku.notification.NotificationModuleApi
import org.springframework.stereotype.Service

@Service
class NotificationService(
    private val renderer: EmailTemplateRenderer,
    private val emailSender: EmailSender,
    private val properties: NotificationEmailProperties,
) : NotificationModuleApi {
    override fun sendInvitationEmail(email: InvitationEmail) {
        val html = renderer.renderInvitation(email)
        emailSender.send(
            properties.from,
            EmailMessage(
                to = email.recipientEmail,
                toName = email.recipientName,
                subject = "You're invited to ${email.eventName}",
                htmlBody = html,
            ),
        )
    }
}
