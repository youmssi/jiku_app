package com.jiku.invitation.internal

enum class RsvpStatus {
    PENDING,
    CONFIRMED,
    DECLINED,

    /**
     * The guest confirmed and then handed their place to someone else (JIKU-64).
     * Terminal: the ticket is cancelled and the attendance slot now belongs to the
     * recipient, so this guest neither counts as attending nor frees capacity.
     */
    TRANSFERRED,
}
