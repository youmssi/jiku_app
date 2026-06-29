package com.jiku.notification.internal

import com.jiku.notification.InvitationEmail
import com.jiku.notification.NotificationModuleApi
import com.jiku.notification.WhatsAppInvitation
import org.springframework.stereotype.Service

@Service
class NotificationService(
    private val emailRenderer: EmailTemplateRenderer,
    private val emailSender: EmailSender,
    private val whatsAppRenderer: WhatsAppTemplateRenderer,
    private val whatsAppSender: WhatsAppSender,
    private val properties: NotificationEmailProperties,
) : NotificationModuleApi {
    override fun sendInvitationEmail(email: InvitationEmail) {
        val html = emailRenderer.renderInvitation(email)
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

    override fun sendInvitationWhatsApp(invitation: WhatsAppInvitation) {
        val text = whatsAppRenderer.renderInvitation(invitation)
        whatsAppSender.send(WhatsAppMessage(to = invitation.recipientPhone, body = text))
    }
}
