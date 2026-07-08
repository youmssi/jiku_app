package com.jiku.event

/**
 * Channels through which an event's invitations may be sent. Configured on the
 * event; the actual sending is implemented in the invitation/notification modules.
 */
enum class InvitationChannel {
    EMAIL,
    WHATSAPP,
}
