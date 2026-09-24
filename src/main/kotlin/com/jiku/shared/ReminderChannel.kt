package com.jiku.shared

/**
 * How a service reminds its clients of an appointment (JIKU-89). The catalog
 * stores the service's choice, the ticket module schedules the reminders and the
 * messaging module sends them, so all three read the same type.
 */
enum class ReminderChannel {
    WHATSAPP,

    /** For clients without WhatsApp or mobile data (JIKU-112). */
    SMS,

    /** WhatsApp first; an SMS only when WhatsApp cannot deliver. */
    WHATSAPP_OR_SMS,
    NONE,
}
