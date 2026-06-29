package com.jiku.notification.internal

/**
 * Everything the invitation email template needs. Built inside the notification
 * module from the inbound [com.jiku.shared.GuestInvitedEvent]; not exposed across
 * module boundaries.
 */
data class InvitationEmail(
    val recipientEmail: String,
    val recipientName: String,
    val eventName: String,
    val eventWhen: String?,
    val eventLocation: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
    val invitationUrl: String,
)

/** Everything the WhatsApp invitation template needs. Notification-internal. */
data class WhatsAppInvitation(
    val recipientPhone: String,
    val recipientName: String,
    val eventName: String,
    val eventWhen: String?,
    val organizerName: String,
    val invitationUrl: String,
)
