package com.jiku.messaging.internal

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

/**
 * Everything the cancellation email template needs. Built inside the notification
 * module from the inbound [com.jiku.shared.EventCancellationNotice].
 */
data class CancellationEmail(
    val recipientEmail: String,
    val recipientName: String,
    val eventName: String,
    val eventWhen: String?,
    val eventLocation: String?,
    val organizerName: String,
    val logoUrl: String?,
)

/** Everything the WhatsApp cancellation template needs. Notification-internal. */
data class WhatsAppCancellation(
    val recipientPhone: String,
    val recipientName: String,
    val eventName: String,
    val eventWhen: String?,
    val organizerName: String,
)

/**
 * Everything the WhatsApp appointment-reminder template needs (JIKU-89). Built
 * inside the notification module from the inbound [com.jiku.shared.ReminderDue].
 * [when] est l'heure du rendez-vous déjà écrite dans le fuseau du service.
 */
data class WhatsAppReminder(
    val recipientPhone: String,
    val recipientName: String,
    val appointmentWhen: String,
    val professionalName: String?,
)
