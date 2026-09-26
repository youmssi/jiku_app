package com.jiku.shared

import java.time.Instant
import java.util.UUID

/**
 * A guest just confirmed and holds a ticket (JIKU-129). Published by the
 * invitation module once the confirmation has committed: by email with a
 * calendar invite, or in WhatsApp when the guest answered or asked there
 * (JIKU-143). The messaging module sends it.
 */
data class TicketConfirmedNotice(
    val guestId: UUID,
    val tenantId: String,
    val eventId: UUID,
    val recipient: String,
    val recipientName: String,
    val eventName: String,
    /** Null when the event has no date yet: no calendar invite is attached then. */
    val eventStart: Instant?,
    val eventEnd: Instant?,
    val eventTimezone: String,
    val eventLocation: String?,
    val categoryName: String?,
    val organizerName: String,
    val primaryColor: String,
    val logoUrl: String?,
    /** The guest's own ticket page. */
    val ticketUrl: String,
    val language: String = MessageLanguage.FRENCH,
    /** Where it is sent: by email by default, or in the guest's WhatsApp chat (JIKU-143). */
    val channel: String = GuestInvitedEvent.CHANNEL_EMAIL,
    /** A PNG of the ticket's QR code, shown as the WhatsApp ticket's image; null when unavailable. */
    val qrImageUrl: String? = null,
)
