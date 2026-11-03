package com.jiku.invitation.internal

enum class InvitationStatus {
    PENDING,
    SENT,
    FAILED,

    /**
     * Delivery was deliberately withheld by a capacity guardrail (JIKU-61's
     * WhatsApp conversation-window safety threshold, or JIKU-62's email provider
     * daily quota) rather than failed; [NotificationQueueSweepJob] retries it
     * automatically once capacity frees up.
     */
    QUEUED,

    /**
     * Ready for the organizer to send from their own WhatsApp (JIKU-281). Jikū
     * never delivers it: it turns [SENT] when the organizer opens the chat.
     */
    PREPARED,
}
