package com.jiku.notification

/**
 * The notification module's public API. Other modules ask it to deliver
 * notifications without knowing how (provider, templating). The send is
 * synchronous from the API's perspective and throws on delivery failure; callers
 * that need async/retry orchestrate that themselves.
 */
interface NotificationModuleApi {
    fun sendInvitationEmail(email: InvitationEmail)
}

/**
 * Everything the invitation email template needs. The caller resolves event and
 * branding details; the notification module only renders and sends.
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
