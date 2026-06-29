package com.jiku.ticketing

import java.time.Instant
import java.util.UUID

/**
 * The ticketing module's public API. The RSVP flow issues a ticket on
 * confirmation and cancels it on decline; check-in (later) resolves a ticket by
 * its code. Reads/writes are tenant-scoped by the persistence-layer filter.
 */
interface TicketingModuleApi {
    fun issueTicket(
        eventId: UUID,
        guestId: UUID,
    ): TicketInfo

    fun cancelByGuest(guestId: UUID)

    fun findByCode(ticketCode: String): TicketInfo?
}

data class TicketInfo(
    val id: UUID,
    val eventId: UUID,
    val guestId: UUID,
    val ticketCode: String,
    val status: String,
    val issuedAt: Instant,
)
