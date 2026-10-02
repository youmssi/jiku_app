package com.jiku.messaging.internal

import com.jiku.messaging.internal.WhatsAppTemplateCall.Companion.parameter
import com.jiku.shared.MessageLanguage
import org.springframework.stereotype.Component

/**
 * Fills the dedicated WhatsApp templates (JIKU-210): each message's
 * parameters, in the order its text in `whatsapp-templates/meta/dedicated.json`
 * expects, with a wording of the guest's language for a value that is missing.
 */
@Component
class WhatsAppTemplateCalls(
    private val catalog: MessageCatalog,
) {
    fun invitation(
        invitation: WhatsAppInvitation,
        language: String,
        withButtons: Boolean,
    ): WhatsAppTemplateCall {
        val lang = MessageLanguage.normalize(language)
        val people = listOf(name(invitation.recipientName, lang), organizer(invitation.organizerName), event(invitation.eventName))
        val date = date(invitation.eventWhen, lang)
        return if (withButtons) {
            WhatsAppTemplateCall(WhatsAppTemplateKind.INVITATION_RSVP, lang, people + date)
        } else {
            WhatsAppTemplateCall(WhatsAppTemplateKind.INVITATION, lang, people + date + parameter(invitation.invitationUrl, "-"))
        }
    }

    fun ticket(
        ticket: WhatsAppInvitation,
        language: String,
    ): WhatsAppTemplateCall {
        val lang = MessageLanguage.normalize(language)
        return WhatsAppTemplateCall(
            WhatsAppTemplateKind.TICKET,
            lang,
            listOf(
                name(ticket.recipientName, lang),
                event(ticket.eventName),
                date(ticket.eventWhen, lang),
                organizer(ticket.organizerName),
                parameter(ticket.invitationUrl, "-"),
            ),
        )
    }

    fun cancellation(
        cancellation: WhatsAppCancellation,
        language: String,
    ): WhatsAppTemplateCall {
        val lang = MessageLanguage.normalize(language)
        return WhatsAppTemplateCall(
            WhatsAppTemplateKind.EVENT_CANCELLED,
            lang,
            listOf(
                name(cancellation.recipientName, lang),
                organizer(cancellation.organizerName),
                event(cancellation.eventName),
                date(cancellation.eventWhen, lang),
            ),
        )
    }

    fun reminder(
        reminder: WhatsAppReminder,
        language: String,
    ): WhatsAppTemplateCall {
        val lang = MessageLanguage.normalize(language)
        return WhatsAppTemplateCall(
            WhatsAppTemplateKind.APPOINTMENT_REMINDER,
            lang,
            listOf(
                name(reminder.recipientName, lang),
                parameter(reminder.professionalName, catalog.text(lang, "whatsapp.yourProvider")),
                date(reminder.appointmentWhen, lang),
            ),
        )
    }

    fun clientCalled(
        clientName: String?,
        counter: String?,
        language: String,
    ): WhatsAppTemplateCall {
        val lang = MessageLanguage.normalize(language)
        return WhatsAppTemplateCall(
            WhatsAppTemplateKind.CLIENT_CALLED,
            lang,
            listOf(name(clientName, lang), parameter(counter, catalog.text(lang, "whatsapp.frontDesk"))),
        )
    }

    private fun name(
        value: String?,
        language: String,
    ) = parameter(value, catalog.text(language, "whatsapp.anyone"))

    private fun organizer(value: String) = parameter(value, "Jikū")

    private fun event(value: String) = parameter(value, "-")

    /** French writes a weekday in lower case mid-sentence ("le mardi 3 novembre"); English keeps its capital. */
    private fun date(
        value: String?,
        language: String,
    ): String {
        val date = parameter(value, catalog.text(language, "whatsapp.dateTbd"))
        return if (language == MessageLanguage.FRENCH) date.replaceFirstChar { it.lowercase() } else date
    }
}
