package com.jiku.ticketing.internal

import com.jiku.ticketing.TicketInfo
import com.jiku.ticketing.TicketingModuleApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TicketingService(
    private val tickets: TicketRepository,
    private val codeGenerator: TicketCodeGenerator,
) : TicketingModuleApi {
    @Transactional
    override fun issueTicket(
        eventId: UUID,
        guestId: UUID,
    ): TicketInfo {
        val existing = tickets.findByGuestId(guestId)
        if (existing != null) {
            if (existing.status == TicketStatus.CANCELLED) {
                existing.status = TicketStatus.ISSUED
                tickets.save(existing)
            }
            return existing.toInfo()
        }
        val ticket = Ticket(eventId = eventId, guestId = guestId, ticketCode = codeGenerator.generate())
        tickets.save(ticket)
        return ticket.toInfo()
    }

    @Transactional
    override fun cancelByGuest(guestId: UUID) {
        tickets.findByGuestId(guestId)?.let {
            it.status = TicketStatus.CANCELLED
            tickets.save(it)
        }
    }

    @Transactional(readOnly = true)
    override fun findByCode(ticketCode: String): TicketInfo? = tickets.findByTicketCode(ticketCode)?.toInfo()
}

private fun Ticket.toInfo(): TicketInfo =
    TicketInfo(
        id = requireNotNull(id),
        eventId = eventId,
        guestId = guestId,
        ticketCode = ticketCode,
        status = status.name,
        issuedAt = issuedAt,
    )
