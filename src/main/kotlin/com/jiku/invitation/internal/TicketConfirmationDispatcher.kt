package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/** A guest's confirmation committed; raised by [RsvpService] inside its transaction. */
data class TicketConfirmed(
    val guestId: UUID,
    val eventId: UUID,
    val tenantId: String,
)

/**
 * Sends a confirmed guest their ticket by email (JIKU-129). Listens for the
 * committed confirmation only, off the request thread so the guest's page never
 * waits on the email provider, and hands delivery to the messaging module.
 * Guests reached only by WhatsApp keep their ticket on the invitation page.
 */
@Component
class TicketConfirmationDispatcher(
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val notices: TicketNotices,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Async("invitationExecutor")
    @TransactionalEventListener
    fun onTicketConfirmed(confirmed: TicketConfirmed) {
        val guest = guests.findById(confirmed.guestId).orElse(null) ?: return
        val email = guest.email?.takeIf { it.isNotBlank() } ?: return
        val event = events.findEvent(confirmed.eventId) ?: return
        eventPublisher.publishEvent(notices.build(guest, event, confirmed.tenantId, email))
    }
}
