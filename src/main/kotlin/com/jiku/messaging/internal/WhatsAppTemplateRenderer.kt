package com.jiku.messaging.internal

import com.jiku.shared.MessageLanguage
import org.springframework.stereotype.Component

/**
 * Renders the plain-text WhatsApp messages (also the SMS texts) from the
 * language's templates in `resources/whatsapp-templates/{lang}/`. Client-facing
 * templates pass through the per-tenant override resolution (JIKU-91) with
 * fallback to the build's default.
 */
@Component
class WhatsAppTemplateRenderer(
    private val clientTemplates: TenantTemplateResolver,
    private val catalog: MessageCatalog,
) {
    fun renderInvitation(
        invitation: WhatsAppInvitation,
        language: String,
    ): String =
        render(
            "invitation",
            language,
            mapOf(
                "guestName" to invitation.recipientName,
                "organizerName" to invitation.organizerName,
                "eventName" to invitation.eventName,
                "eventWhen" to joined(language, "whatsapp.on", invitation.eventWhen?.let { inSentence(it, language) }),
                "invitationUrl" to invitation.invitationUrl,
            ),
        )

    /** The ticket itself, for an event that sends tickets directly (ADR 105). */
    fun renderTicket(
        ticket: WhatsAppInvitation,
        language: String,
    ): String =
        render(
            "ticket",
            language,
            mapOf(
                "guestName" to ticket.recipientName,
                "organizerName" to ticket.organizerName,
                "eventName" to ticket.eventName,
                "eventWhen" to joined(language, "whatsapp.on", ticket.eventWhen?.let { inSentence(it, language) }),
                "ticketUrl" to ticket.invitationUrl,
            ),
        )

    fun renderCancellation(
        cancellation: WhatsAppCancellation,
        language: String,
    ): String =
        render(
            "event-cancelled",
            language,
            mapOf(
                "guestName" to cancellation.recipientName,
                "organizerName" to cancellation.organizerName,
                "eventName" to cancellation.eventName,
                "eventWhen" to joined(language, "whatsapp.on", cancellation.eventWhen?.let { inSentence(it, language) }),
            ),
        )

    fun renderAppointmentReminder(
        reminder: WhatsAppReminder,
        language: String,
    ): String =
        render(
            "appointment-reminder",
            language,
            mapOf(
                "clientName" to reminder.recipientName,
                "professional" to joined(language, "whatsapp.with", reminder.professionalName),
                "when" to reminder.appointmentWhen,
            ),
        )

    /** "It's your turn" for a waiting client (JIKU-114); also the SMS text. */
    fun renderClientCalled(
        clientName: String,
        counter: String?,
        language: String,
    ): String =
        render(
            "client-called",
            language,
            mapOf(
                "clientName" to clientName,
                "counter" to joined(language, "whatsapp.at", counter),
            ),
        )

    private fun render(
        name: String,
        language: String,
        values: Map<String, String>,
    ): String {
        val file = requireNotNull(ClientTemplates.definition(name)?.whatsappFile) { "No WhatsApp template for $name" }
        return clientTemplates.render(name, ClientTemplates.CHANNEL_WHATSAPP, catalog.whatsAppTemplate(language, file), values)
    }

    private fun joined(
        language: String,
        joinerKey: String,
        value: String?,
    ): String = value?.takeIf { it.isNotBlank() }?.let { catalog.text(language, joinerKey) + it }.orEmpty()

    /** French writes a weekday in lower case mid-sentence ("le mardi 3 novembre"); English keeps its capital. */
    private fun inSentence(
        date: String,
        language: String,
    ): String = if (MessageLanguage.normalize(language) == MessageLanguage.FRENCH) date.replaceFirstChar { it.lowercase() } else date
}
