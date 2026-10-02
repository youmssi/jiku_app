package com.jiku.messaging.internal

/**
 * A WhatsApp invitation from the Jikū number to a guest the organizer did not
 * state agreed to hear from it (JIKU-213). Not retried: the organizer confirms
 * consent for the event's guests, or invites them by email.
 */
class WhatsAppConsentMissingException(
    message: String,
) : RuntimeException(message)
