package com.jiku.shared

import java.time.Instant
import java.util.UUID

/**
 * A guest just confirmed and holds a ticket (JIKU-129). Published by the
 * invitation module once the confirmation has committed, for guests with an
 * email address; the messaging module sends the ticket with a calendar invite.
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
)
