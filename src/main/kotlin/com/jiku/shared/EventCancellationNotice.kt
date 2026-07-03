package com.jiku.shared

import java.util.UUID

/**
 * One guest to be told their event was cancelled, on one channel — the channel
 * that originally carried their invitation. Published by the invitation module
 * (which owns guests and knows what was sent to whom) and consumed by the
 * notification module (which renders, sends, retries and audits), mirroring
 * [GuestInvitedEvent] so neither module depends on the other.
 */
data class EventCancellationNotice(
    /** The originating invitation; the notification audit log references it. */
    val invitationId: UUID,
    val tenantId: String,
    /** One of [GuestInvitedEvent.CHANNEL_EMAIL] / [GuestInvitedEvent.CHANNEL_WHATSAPP]. */
    val channel: String,
    val recipient: String,
    val recipientName: String,
    val eventName: String,
    val eventWhen: String?,
    val eventLocation: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
)
