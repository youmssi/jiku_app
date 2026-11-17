package com.jiku.invitation.internal

import com.jiku.shared.OrganizationClosed
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Erases the guests, open-invitation answers and ticket buyers of an
 * organization deleted with its owner's account (JIKU-316), through the same
 * erasure the retention policy uses. Runs inside the closing transaction, with
 * the organization bound as the tenant.
 */
@Component
class OrganizationErasure(
    private val guests: GuestRepository,
    private val erasure: GuestErasureService,
    private val openResponses: OpenResponseRepository,
    private val orders: TicketOrderRepository,
) {
    @EventListener
    fun on(event: OrganizationClosed) {
        guests.findByPersonalDataErasedFalse().forEach { erasure.eraseGuest(requireNotNull(it.id), ErasureReason.ACCOUNT_DELETION) }
        openResponses.findAll().filterNot { it.erased }.forEach {
            it.erase()
            openResponses.save(it)
        }
        orders.findAll().filterNot { it.erased }.forEach {
            it.erase()
            orders.save(it)
        }
    }
}
