package com.jiku.ticketing.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TicketRepository : JpaRepository<Ticket, UUID> {
    fun findByGuestId(guestId: UUID): Ticket?

    fun findByTicketCode(ticketCode: String): Ticket?
}
