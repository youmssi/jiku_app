package com.jiku.invitation.internal

data class RsvpView(
    val eventName: String,
    val eventWhen: String?,
    val eventLocation: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
    val guestName: String,
    val status: String,
    val ticketCode: String?,
    /** The event's own lifecycle status; CANCELLED renders a cancellation notice. */
    val eventStatus: String? = null,
)
