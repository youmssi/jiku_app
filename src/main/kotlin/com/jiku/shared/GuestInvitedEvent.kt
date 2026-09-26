package com.jiku.shared

import java.util.UUID

/**
 * Published by the invitation module when a guest's invitation is ready to send,
 * and consumed by the notification module which owns delivery. It lives in
 * `shared` (a shared kernel) so neither module depends on the other — the
 * invitation flow is fully event-driven and the Modulith boundary stays acyclic.
 *
 * The invitation module resolves all presentation content (it owns the signed
 * link); notification only renders, sends, retries and audits.
 */
data class GuestInvitedEvent(
    val invitationId: UUID,
    val tenantId: String,
    /** Carried so notification can attribute delivery cost (e.g. WhatsApp) back to the event. */
    val eventId: UUID,
    val channel: String,
    val recipient: String,
    val recipientName: String,
    val eventName: String,
    val eventWhen: String?,
    val eventLocation: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
    val invitationUrl: String,
    /** The organizer's language, which the invitation is written in. */
    val language: String = MessageLanguage.FRENCH,
    /**
     * Set when the event sends tickets directly (ADR 105): the guest is already
     * confirmed and receives the ticket itself instead of an invitation.
     */
    val ticket: TicketConfirmedNotice? = null,
    /**
     * The guest answers from the chat (ADR 105, INTERACTIVE mode): a WhatsApp
     * invitation then carries accept and decline buttons.
     */
    val interactive: Boolean = false,
) {
    companion object {
        const val CHANNEL_EMAIL = "EMAIL"
        const val CHANNEL_WHATSAPP = "WHATSAPP"
    }
}
