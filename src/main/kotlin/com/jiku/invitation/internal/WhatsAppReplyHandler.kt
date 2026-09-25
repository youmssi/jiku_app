package com.jiku.invitation.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.MessageLanguage
import com.jiku.shared.TenantContext
import com.jiku.shared.WhatsAppReplyOutcome
import com.jiku.shared.WhatsAppRsvpReply
import com.jiku.shared.WhatsAppTicketRequest
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Applies what a guest answered in WhatsApp (JIKU-143), with the same rules as
 * their invitation page: accepting takes a seat and sends the ticket back in the
 * chat, declining frees it. A ticket asked for by keyword is sent again when the
 * guest holds one. Anything that does not end with a ticket is answered with a
 * [WhatsAppReplyOutcome]. Runs off the webhook's thread, so Meta is
 * acknowledged at once.
 */
@Component
class WhatsAppReplyHandler(
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val rsvpService: RsvpService,
    private val ticketNotices: TicketNotices,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Async("invitationExecutor")
    @EventListener
    fun onRsvpReply(reply: WhatsAppRsvpReply) =
        inTenant(reply.tenantId, reply.invitationId) { guest, event, phone ->
            val guestId = requireNotNull(guest.id)
            if (event.status != EventInfo.STATUS_PUBLISHED) {
                answer(phone, WhatsAppReplyOutcome.Kind.CLOSED, event, reply.tenantId)
                return@inTenant
            }
            try {
                if (reply.accepted) {
                    rsvpService.confirm(guestId, event.id)
                    sendTicket(guest, event, reply.tenantId, phone)
                } else {
                    rsvpService.decline(guestId, event.id)
                    answer(phone, WhatsAppReplyOutcome.Kind.DECLINED, event, reply.tenantId)
                }
            } catch (ex: ResponseStatusException) {
                val kind = if (ex.statusCode == HttpStatus.CONFLICT) WhatsAppReplyOutcome.Kind.FULL else WhatsAppReplyOutcome.Kind.CLOSED
                answer(phone, kind, event, reply.tenantId)
            }
        }

    @Async("invitationExecutor")
    @EventListener
    fun onTicketRequest(request: WhatsAppTicketRequest) =
        inTenant(request.tenantId, request.invitationId) { guest, event, phone ->
            if (guest.rsvpStatus == RsvpStatus.CONFIRMED && !guest.personalDataErased) {
                sendTicket(guest, event, request.tenantId, phone)
            } else {
                answer(phone, WhatsAppReplyOutcome.Kind.NO_TICKET, event, request.tenantId)
            }
        }

    private fun sendTicket(
        guest: Guest,
        event: EventInfo,
        tenantId: String,
        phone: String,
    ) {
        eventPublisher.publishEvent(ticketNotices.build(guest, event, tenantId, phone, GuestInvitedEvent.CHANNEL_WHATSAPP))
    }

    private fun answer(
        phone: String,
        kind: WhatsAppReplyOutcome.Kind,
        event: EventInfo,
        tenantId: String,
    ) {
        val country = tenants.findTenant(UUID.fromString(tenantId))?.country
        eventPublisher.publishEvent(WhatsAppReplyOutcome(phone, kind, event.name, MessageLanguage.forCountry(country)))
    }

    /** Runs [block] with the reply's tenant bound, once the invitation, its guest and event are found. */
    private fun inTenant(
        tenantId: String,
        invitationId: UUID,
        block: (Guest, EventInfo, String) -> Unit,
    ) {
        TenantContext.set(tenantId)
        try {
            val invitation = invitations.findById(invitationId).orElse(null) ?: return
            val guest = guests.findById(invitation.guestId).orElse(null) ?: return
            val phone = guest.phoneNumber ?: return
            val event = events.findEvent(invitation.eventId) ?: return
            block(guest, event, phone)
        } finally {
            TenantContext.clear()
        }
    }
}
