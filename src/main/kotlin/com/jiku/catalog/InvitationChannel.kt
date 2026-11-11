package com.jiku.catalog

/**
 * Channels through which an event's invitations may be sent. Configured on the
 * event; the actual sending is implemented in the invitation/notification modules.
 */
enum class InvitationChannel {
    EMAIL,
    WHATSAPP,
    ;

    companion object {
        /**
         * One guest is invited on one channel (JIKU-273, ADR 110): the primary first
         * (WhatsApp when none is set and it is enabled), then the other enabled one
         * only when [fallback] is on.
         */
        fun channelOrder(
            enabled: Set<InvitationChannel>,
            primary: InvitationChannel?,
            fallback: Boolean,
        ): List<InvitationChannel> {
            val first = primary?.takeIf { it in enabled } ?: listOf(WHATSAPP, EMAIL).firstOrNull { it in enabled } ?: return emptyList()
            return if (fallback) listOf(first) + enabled.filter { it != first }.sorted() else listOf(first)
        }
    }
}
