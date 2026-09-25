package com.jiku.shared

/**
 * The WhatsApp appointment reminders a free services plan includes each
 * month (ADR 105: Solo includes 50). Exposed in `shared` so the messaging
 * module can check it without depending on the billing module, which provides
 * the implementation.
 */
interface ReminderAllowanceGate {
    /** Whether [tenantId] may still send a WhatsApp reminder this month. */
    fun canSendWhatsAppReminder(tenantId: String): Boolean

    /** Counts a WhatsApp reminder [tenantId] just sent. */
    fun recordWhatsAppReminder(tenantId: String)
}
