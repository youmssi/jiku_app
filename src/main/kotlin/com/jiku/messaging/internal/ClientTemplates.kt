package com.jiku.messaging.internal

/**
 * Gabarits adressés aux clients, surchargeables par tenant (JIKU-91). Chaque
 * définition fixe son nom, les canaux disponibles, les variables {{…}} exposées à
 * l'éditeur pour chaque canal et le fichier par défaut du build (repli). Les
 * variables listées correspondent exactement aux valeurs injectées à l'envoi :
 * un gabarit invalide (variable inconnue) replie sur le défaut et alerte.
 */
object ClientTemplates {
    const val CHANNEL_EMAIL = "EMAIL"
    const val CHANNEL_WHATSAPP = "WHATSAPP"

    data class Variable(
        val name: String,
        val label: String,
        val sample: String,
        val required: Boolean = false,
    )

    data class Definition(
        val name: String,
        val label: String,
        val channels: List<String>,
        val emailVariables: List<Variable>?,
        val whatsappVariables: List<Variable>?,
        val emailFile: String?,
        val whatsappFile: String?,
    ) {
        fun defaultFile(channel: String): String? =
            when (channel) {
                CHANNEL_EMAIL -> emailFile
                CHANNEL_WHATSAPP -> whatsappFile
                else -> null
            }

        fun variables(channel: String): List<Variable> =
            when (channel) {
                CHANNEL_EMAIL -> emailVariables
                CHANNEL_WHATSAPP -> whatsappVariables
                else -> null
            }.orEmpty()
    }

    val definitions: List<Definition> =
        listOf(
            Definition(
                name = "invitation",
                label = "Guest invitation",
                channels = listOf(CHANNEL_EMAIL, CHANNEL_WHATSAPP),
                emailVariables =
                    listOf(
                        Variable("guestName", "Guest name", "Awa Diallo", required = true),
                        Variable("organizerName", "Organizer name", "Salon Aminata", required = true),
                        Variable("eventName", "Event / offer name", "Coupe + soin", required = true),
                        Variable("eventWhen", "Date and time", "Tuesday 3 Nov at 15:00"),
                        Variable("eventLocation", "Location", "Avenue de la République"),
                        Variable("invitationUrl", "Confirmation link", "https://jiku.app/r/8f3k2a", required = true),
                        Variable("primaryColor", "Brand color", "#1E293B"),
                        Variable("eventDetails", "Formatted date/location block (advanced)", ""),
                        Variable("logoBlock", "Logo image (advanced)", ""),
                    ),
                whatsappVariables =
                    listOf(
                        Variable("guestName", "Guest name", "Awa Diallo", required = true),
                        Variable("organizerName", "Organizer name", "Salon Aminata", required = true),
                        Variable("eventName", "Event / offer name", "Coupe + soin", required = true),
                        Variable("eventWhen", "Date and time", "on Tuesday 3 Nov at 15:00"),
                        Variable("invitationUrl", "Confirmation link", "https://jiku.app/r/8f3k2a", required = true),
                    ),
                emailFile = "invitation.html",
                whatsappFile = "invitation.txt",
            ),
            Definition(
                name = "event-cancelled",
                label = "Event cancellation",
                channels = listOf(CHANNEL_EMAIL, CHANNEL_WHATSAPP),
                emailVariables =
                    listOf(
                        Variable("guestName", "Guest name", "Awa Diallo", required = true),
                        Variable("organizerName", "Organizer name", "Salon Aminata", required = true),
                        Variable("eventName", "Event / offer name", "Coupe + soin", required = true),
                        Variable("eventWhen", "Date and time", "Tuesday 3 Nov at 15:00"),
                        Variable("eventLocation", "Location", "Avenue de la République"),
                        Variable("primaryColor", "Brand color", "#1E293B"),
                        Variable("eventDetails", "Formatted date/location block (advanced)", ""),
                        Variable("logoBlock", "Logo image (advanced)", ""),
                    ),
                whatsappVariables =
                    listOf(
                        Variable("guestName", "Guest name", "Awa Diallo", required = true),
                        Variable("organizerName", "Organizer name", "Salon Aminata", required = true),
                        Variable("eventName", "Event / offer name", "Coupe + soin", required = true),
                        Variable("eventWhen", "Date and time", "on Tuesday 3 Nov at 15:00"),
                    ),
                emailFile = "event-cancelled.html",
                whatsappFile = "event-cancelled.txt",
            ),
            Definition(
                name = "appointment-reminder",
                label = "Appointment reminder (WhatsApp)",
                channels = listOf(CHANNEL_WHATSAPP),
                emailVariables = null,
                whatsappVariables =
                    listOf(
                        Variable("clientName", "Client name", "Awa Diallo", required = true),
                        Variable("when", "Appointment date and time", "Tuesday 3 Nov at 15:00", required = true),
                        Variable("professional", "Professional name", "with Aminata"),
                    ),
                emailFile = null,
                whatsappFile = "appointment-reminder.txt",
            ),
            Definition(
                name = "client-called",
                label = "Your turn in the line (WhatsApp / SMS)",
                channels = listOf(CHANNEL_WHATSAPP),
                emailVariables = null,
                whatsappVariables =
                    listOf(
                        Variable("clientName", "Client name", "Awa Diallo", required = true),
                        Variable("counter", "Counter to go to", " at counter 4"),
                    ),
                emailFile = null,
                whatsappFile = "client-called.txt",
            ),
        )

    fun definition(name: String): Definition? = definitions.firstOrNull { it.name == name }
}
