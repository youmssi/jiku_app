package com.jiku.shared

import java.util.UUID

/**
 * The commission on tickets sold (JIKU-178, ADR 104 §8): what the
 * organization's paid batches still let it sell in a category, and the record
 * of each ticket sold. Exposed in `shared` so the ticket-sale flow can ask
 * without depending on the money module, which implements it.
 */
interface CommissionGate {
    /**
     * Tickets of [ticketTypeId] the category's batches still cover, or null
     * when the batches do not limit sales — the day of the event, when a sale
     * never pauses and what is sold beyond the batches is owed afterwards.
     */
    fun coveredPlaces(
        eventId: UUID,
        ticketTypeId: UUID,
    ): Int?

    /**
     * Records [quantity] tickets of [ticketTypeId] as sold. Never refuses: the
     * client already paid the organization. What no batch covers is owed.
     */
    fun consume(
        eventId: UUID,
        ticketTypeId: UUID,
        quantity: Int,
    )
}
