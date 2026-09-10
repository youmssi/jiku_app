package com.jiku.ticket.internal

import com.jiku.shared.ServiceDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Removes every appointment ticket of a deleted service. Runs synchronously
 * inside the deleting transaction (MANDATORY makes that explicit), so the
 * service and its day-line tickets disappear together; appointment reminders
 * follow through the database's FK cascade on ticket_id.
 */
@Component
class ServiceDeletionListener(
    private val tickets: TicketRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onServiceDeleted(event: ServiceDeletedEvent) {
        tickets.deleteAll(tickets.findByServiceId(event.serviceId))
    }
}
