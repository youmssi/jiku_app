package com.jiku.ticket.internal

import com.jiku.shared.DemoSeedContext
import com.jiku.shared.DemoSeedContributor
import com.jiku.shared.DemoSeedPlan
import org.springframework.stereotype.Component

/**
 * Issues a ticket for every confirmed demo guest, marking it checked in (with the
 * gate and time from the plan) for the guests who attended the past event. Runs
 * after the invitation contributor, so the guest ids it needs are already in the
 * shared context.
 */
@Component
class TicketingDemoSeedContributor(
    private val tickets: TicketRepository,
    private val codeGenerator: TicketCodeGenerator,
) : DemoSeedContributor {
    override val order = 30

    override fun wipe() {
        tickets.deleteAll()
    }

    override fun seed(context: DemoSeedContext) {
        DemoSeedPlan.guests
            .filter { it.rsvpStatus == DemoSeedPlan.STATUS_CONFIRMED }
            .forEach { planned ->
                val ticket =
                    Ticket(
                        eventId = context.eventIds.getValue(planned.eventKey),
                        guestId = context.guestIds.getValue(planned.email),
                        ticketCode = codeGenerator.generate(),
                    )
                planned.checkedInBy?.let { gate ->
                    ticket.status = TicketStatus.CHECKED_IN
                    ticket.checkedInAt =
                        DemoSeedPlan.eventStart(planned.eventKey).plus(requireNotNull(planned.checkedInAfter))
                    ticket.checkedInBy = gate
                }
                tickets.save(ticket)
            }
    }
}
