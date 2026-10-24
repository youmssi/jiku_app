package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.async.Executors
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
 * Sends a confirmed guest their ticket (JIKU-129) on the channel their
 * invitation reached them on (JIKU-273): in WhatsApp for a guest invited there,
 * by email otherwise. Listens for the committed confirmation only, off the
 * request thread so the guest's page never waits on a provider, and hands
 * delivery to the messaging module. A guest with no usable contact keeps the
 * ticket on the invitation page.
 */
@Component
class TicketConfirmationDispatcher(
    private val guests: GuestRepository,
    private val invitations: InvitationRepository,
    private val events: EventModuleApi,
    private val notices: TicketNotices,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Async(Executors.BULK)
    @TransactionalEventListener
    fun onTicketConfirmed(confirmed: TicketConfirmed) {
        val guest = guests.findById(confirmed.guestId).orElse(null) ?: return
        val event = events.findEvent(confirmed.eventId) ?: return
        val phone = guest.phoneNumber?.takeIf { InvitationDispatchWorker.E164.matches(it) }
        val email = guest.email?.takeIf { it.isNotBlank() }
        val notice =
            if (invitedOn(guest) == InvitationChannel.WHATSAPP && phone != null) {
                notices.build(guest, event, confirmed.tenantId, phone, GuestInvitedEvent.CHANNEL_WHATSAPP)
            } else {
                email?.let { notices.build(guest, event, confirmed.tenantId, it) }
            } ?: return
        eventPublisher.publishEvent(notice)
    }

    /** The channel of the guest's delivered invitation, the latest one when there are two. */
    private fun invitedOn(guest: Guest): InvitationChannel? =
        invitations
            .findByGuestId(requireNotNull(guest.id))
            .filter { it.status == InvitationStatus.SENT }
            .maxByOrNull { it.dispatchedAt ?: it.createdAt }
            ?.channel
}
