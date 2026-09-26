package com.jiku.shared

import java.util.UUID

/**
 * A guest tapped accept or decline on a WhatsApp invitation (JIKU-143).
 * Published by the messaging module, which owns the inbound webhook, once the
 * reply is matched to the invitation it answers and to the number it was sent
 * to; the invitation module applies it as if the guest had answered on their
 * page.
 */
data class WhatsAppRsvpReply(
    val tenantId: String,
    val invitationId: UUID,
    val accepted: Boolean,
)

/** A guest wrote the ticket keyword in WhatsApp: their latest invitation's ticket is sent again (JIKU-143). */
data class WhatsAppTicketRequest(
    val tenantId: String,
    val invitationId: UUID,
)

/**
 * The answer to a WhatsApp reply that did not end with a ticket in the chat
 * (JIKU-143), sent back by the messaging module inside the conversation the
 * guest just opened.
 */
data class WhatsAppReplyOutcome(
    val phone: String,
    val kind: Kind,
    val eventName: String,
    val language: String,
) {
    enum class Kind {
        /** The decline was recorded. */
        DECLINED,

        /** The event has no seat left. */
        FULL,

        /** The event was cancelled or is not open for answers. */
        CLOSED,

        /** The guest holds no ticket for that invitation yet. */
        NO_TICKET,
    }
}
