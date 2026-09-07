package com.jiku.messaging.internal

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Renders the plain-text WhatsApp messages from templates kept in
 * `resources/whatsapp-templates/`. Client-facing templates pass through the
 * per-tenant override resolution (JIKU-91) with fallback to the build's default.
 */
@Component
class WhatsAppTemplateRenderer(
    private val clientTemplates: TenantTemplateResolver,
) {
    private val invitationTemplate: String by lazy {
        ClassPathResource("whatsapp-templates/invitation.txt").inputStream.bufferedReader().use { it.readText() }
    }

    private val cancellationTemplate: String by lazy {
        ClassPathResource("whatsapp-templates/event-cancelled.txt").inputStream.bufferedReader().use { it.readText() }
    }

    private val appointmentReminderTemplate: String by lazy {
        ClassPathResource("whatsapp-templates/appointment-reminder.txt").inputStream.bufferedReader().use { it.readText() }
    }

    fun renderInvitation(invitation: WhatsAppInvitation): String {
        val eventWhen = invitation.eventWhen?.let { " on $it" } ?: ""
        return clientTemplates.render(
            ClientTemplates.definition("invitation").let { requireNotNull(it) }.name,
            ClientTemplates.CHANNEL_WHATSAPP,
            invitationTemplate,
            mapOf(
                "guestName" to invitation.recipientName,
                "organizerName" to invitation.organizerName,
                "eventName" to invitation.eventName,
                "eventWhen" to eventWhen,
                "invitationUrl" to invitation.invitationUrl,
            ),
        )
    }

    fun renderCancellation(cancellation: WhatsAppCancellation): String {
        val eventWhen = cancellation.eventWhen?.let { " on $it" } ?: ""
        return clientTemplates.render(
            ClientTemplates.definition("event-cancelled").let { requireNotNull(it) }.name,
            ClientTemplates.CHANNEL_WHATSAPP,
            cancellationTemplate,
            mapOf(
                "guestName" to cancellation.recipientName,
                "organizerName" to cancellation.organizerName,
                "eventName" to cancellation.eventName,
                "eventWhen" to eventWhen,
            ),
        )
    }

    fun renderAppointmentReminder(reminder: WhatsAppReminder): String {
        val professional = reminder.professionalName?.let { " with $it" } ?: ""
        return clientTemplates.render(
            ClientTemplates.definition("appointment-reminder").let { requireNotNull(it) }.name,
            ClientTemplates.CHANNEL_WHATSAPP,
            appointmentReminderTemplate,
            mapOf(
                "clientName" to reminder.recipientName,
                "professional" to professional,
                "when" to reminder.appointmentWhen,
            ),
        )
    }
}
