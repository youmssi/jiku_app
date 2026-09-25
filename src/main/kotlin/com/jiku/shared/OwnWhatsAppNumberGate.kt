package com.jiku.shared

/**
 * Whether the current tenant may send from its own WhatsApp number (ADR 105):
 * included in the Organisation plan and the Organizer Pack, a monthly add-on
 * otherwise. Exposed in `shared` so the messaging module can check it without
 * depending on the billing module, which provides the implementation.
 */
interface OwnWhatsAppNumberGate {
    fun ownNumberAllowed(): Boolean
}
