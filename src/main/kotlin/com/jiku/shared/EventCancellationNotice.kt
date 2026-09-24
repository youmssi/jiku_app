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
    /** Carried so notification can attribute delivery cost (e.g. WhatsApp) back to the event. */
    val eventId: UUID,
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
    /** The organizer's language, which the notice is written in. */
    val language: String = MessageLanguage.FRENCH,
)
