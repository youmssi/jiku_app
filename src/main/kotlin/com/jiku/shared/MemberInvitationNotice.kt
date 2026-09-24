package com.jiku.shared

/**
 * A member invitation email request (JIKU-50), published by the tenant module
 * and consumed by the notification module — the same publisher/consumer split
 * as [AccountNotice]. The action URL already carries the single-use token.
 */
data class MemberInvitationNotice(
    val email: String,
    val organizationName: String,
    val inviterEmail: String,
    val role: String,
    val actionUrl: String,
    /** The inviting organization's language. */
    val language: String = MessageLanguage.FRENCH,
)
