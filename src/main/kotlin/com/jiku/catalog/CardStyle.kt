package com.jiku.catalog

/**
 * How an event's guest-facing surfaces look (JIKU-194): the shared card, the
 * answer page, the link preview, the ticket and the WhatsApp card message. The
 * organizer picks one; the typography, spacing and contrasts of each are fixed
 * by Jikū, so every choice stays legible.
 */
enum class CardStyle {
    ELEGANT,
    MODERN,
    FESTIVE,
}
