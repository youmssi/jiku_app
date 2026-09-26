package com.jiku.catalog

/**
 * How an event's guests receive their ticket (ADR 105, decision 3), chosen by
 * the organizer per event.
 */
enum class DeliveryMode {
    /** The guest gets a link and answers on their invitation page (the default). */
    LINK,

    /** The guest is confirmed on sending and gets the ticket itself, ready to scan. */
    DIRECT_TICKET,

    /**
     * Like [LINK], but a WhatsApp guest answers with a button in the chat and
     * gets the ticket there, QR code included (ADR 105, decision 4).
     */
    INTERACTIVE,
}
