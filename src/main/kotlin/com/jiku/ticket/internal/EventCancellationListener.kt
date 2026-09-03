package com.jiku.ticket.internal

import com.jiku.shared.EventCancelledEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Invalidates every ticket of a cancelled event. Runs synchronously inside the
 * cancelling transaction (MANDATORY makes that explicit), so the event's status
 * change and the ticket invalidations commit or roll back together — there is no
 * intermediate state where the event is cancelled but a ticket remains valid.
 *
 * Tickets are loaded through the tenant-filtered repository rather than bulk
 * JPQL, which would bypass the persistence-layer tenant filter.
 */
@Component
class EventCancellationListener(
    private val tickets: TicketRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onEventCancelled(event: EventCancelledEvent) {
        val affected =
            tickets.findByEventId(event.eventId).onEach {
                it.status = TicketStatus.CANCELLED
            }
        tickets.saveAll(affected)
    }
}
