package com.jiku.ticket.internal

import com.jiku.shared.EventDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Removes every ticket of a deleted event. Runs synchronously inside the
 * deleting transaction (MANDATORY makes that explicit), so the event and its
 * tickets disappear together rather than leaving orphaned tickets behind.
 */
@Component
class TicketEventDeletionListener(
    private val tickets: TicketRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onEventDeleted(event: EventDeletedEvent) {
        tickets.deleteAll(tickets.findByEventId(event.eventId))
    }
}
