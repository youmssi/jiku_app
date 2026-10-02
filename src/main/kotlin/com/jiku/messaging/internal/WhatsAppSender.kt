package com.jiku.messaging.internal

/** A rendered WhatsApp message ready for delivery by a provider adapter. */
data class WhatsAppMessage(
    val to: String,
    val body: String,
    /** Quick-reply buttons under the text (JIKU-143); a tap comes back through the webhook with the button's id. */
    val buttons: List<WhatsAppButton> = emptyList(),
    /** A public PNG or JPEG shown above the text, such as a ticket's QR code (JIKU-143). */
    val imageUrl: String? = null,
    /**
     * The approved template made for this message (JIKU-210), with its
     * parameters. A sender that has the dedicated templates uses it; [body]
     * stays the text for a session message, the content check and SMS.
     */
    val template: WhatsAppTemplateCall? = null,
    /** An answer to someone who just wrote: always a session message, never a template. */
    val session: Boolean = false,
)

/**
 * The dedicated WhatsApp templates (JIKU-210): one per use, in French and
 * English, with fixed text signed by Jikū. Meta reviews each one and pauses
 * each one on its own, so a problem with reminders no longer stops invitations.
 * [key] is the name after the configured prefix, versioned so a wording change
 * is a new template rather than an edit Meta must review again.
 */
enum class WhatsAppTemplateKind(
    val key: String,
) {
    INVITATION("invitation_v1"),
    INVITATION_RSVP("invitation_rsvp_v1"),
    TICKET("ticket_v1"),
    EVENT_CANCELLED("event_cancelled_v1"),
    APPOINTMENT_REMINDER("appointment_reminder_v1"),
    CLIENT_CALLED("client_called_v1"),
}

/** A dedicated template to send, in [language], with its body [parameters] in order. */
data class WhatsAppTemplateCall(
    val kind: WhatsAppTemplateKind,
    val language: String,
    val parameters: List<String>,
) {
    companion object {
        private val BLANKS = Regex("\\s+")
        private const val MAX_PARAMETER = 300

        /**
         * Meta refuses a parameter that is empty or holds a line break, a tab or
         * more than four spaces in a row: each value is put on one line, and a
         * blank one takes its [fallback].
         */
        fun parameter(
            value: String?,
            fallback: String,
        ): String =
            value
                ?.replace(BLANKS, " ")
                ?.trim()
                ?.take(MAX_PARAMETER)
                ?.takeIf { it.isNotEmpty() } ?: fallback
    }
}

/** One quick-reply button: [id] is what the webhook receives, [title] what the guest sees (20 characters at most). */
data class WhatsAppButton(
    val id: String,
    val title: String,
)

/**
 * Provider adapter port for WhatsApp. The transport is selected with
 * `jiku.whatsapp.transport`: [LoggingWhatsAppSender] (`log`, default) or
 * [MetaCloudWhatsAppSender] (`meta`, the WhatsApp Cloud API). Adapters throw
 * [WhatsAppDeliveryException] on failure so the orchestration retry engages.
 * Further providers (Twilio, 360dialog, …) plug in as additional adapters.
 */
interface WhatsAppSender {
    /** Sends [message]; returns the id the provider gave it, which later delivery statuses refer to, when it has one. */
    fun send(message: WhatsAppMessage): String?
}
