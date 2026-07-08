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
    /** True once the guest has erased their personal data (JIKU-36). */
    val erased: Boolean = false,
)
