package com.jiku.notification.internal

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Renders the plain-text WhatsApp invitation from a template kept in
 * `resources/whatsapp-templates/`.
 */
@Component
class WhatsAppTemplateRenderer {
    private val invitationTemplate: String by lazy {
        ClassPathResource("whatsapp-templates/invitation.txt").inputStream.bufferedReader().use { it.readText() }
    }

    private val cancellationTemplate: String by lazy {
        ClassPathResource("whatsapp-templates/event-cancelled.txt").inputStream.bufferedReader().use { it.readText() }
    }

    fun renderInvitation(invitation: WhatsAppInvitation): String =
        invitationTemplate
            .replace("{{guestName}}", invitation.recipientName)
            .replace("{{organizerName}}", invitation.organizerName)
            .replace("{{eventName}}", invitation.eventName)
            .replace("{{eventWhen}}", invitation.eventWhen?.let { " on $it" } ?: "")
            .replace("{{invitationUrl}}", invitation.invitationUrl)

    fun renderCancellation(cancellation: WhatsAppCancellation): String =
        cancellationTemplate
            .replace("{{guestName}}", cancellation.recipientName)
            .replace("{{organizerName}}", cancellation.organizerName)
            .replace("{{eventName}}", cancellation.eventName)
            .replace("{{eventWhen}}", cancellation.eventWhen?.let { " on $it" } ?: "")
}
