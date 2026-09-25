package com.jiku.invitation.internal

import com.jiku.catalog.DeliveryMode
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.MessageLanguage
import com.jiku.shared.TenantContext
import com.jiku.shared.UsageAllowanceGate
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Prepares a single invitation for delivery: resolves the guest, event and tenant
 * branding, builds the signed invitation link, and publishes a [GuestInvitedEvent]
 * for the notification module to deliver. It never sends or retries itself — that
 * is the notification module's job; this module only owns the invitation's
 * lifecycle, updated from the delivery result (see [InvitationResultListener]).
 */
@Component
class InvitationDispatchWorker(
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val tokenService: InvitationTokenService,
    private val properties: InvitationSendProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val rsvpService: RsvpService,
    private val ticketNotices: TicketNotices,
    private val allowanceGate: UsageAllowanceGate,
) {
    @Transactional
    fun process(invitationId: UUID) {
        val invitation = invitations.findById(invitationId).orElse(null) ?: return
        if (invitation.status == InvitationStatus.SENT) {
            return
        }
        val guest = guests.findById(invitation.guestId).orElse(null)
        if (guest == null) {
            fail(invitation, "Guest not found")
            return
        }

        val channelName: String
        val recipient: String
        when (invitation.channel) {
            InvitationChannel.EMAIL -> {
                val email = guest.email
                if (email == null) {
                    fail(invitation, "Guest has no email address")
                    return
                }
                channelName = GuestInvitedEvent.CHANNEL_EMAIL
                recipient = email
            }

            InvitationChannel.WHATSAPP -> {
                val phone = guest.phoneNumber
                if (phone == null || !E164.matches(phone)) {
                    fail(invitation, "Guest has no valid WhatsApp number")
                    return
                }
                channelName = GuestInvitedEvent.CHANNEL_WHATSAPP
                recipient = phone
            }
        }

        val event = events.findEvent(invitation.eventId)
        val tenantId = TenantContext.get().orEmpty()
        val tenant = tenantId.takeIf { it.isNotEmpty() }?.let { tenants.findTenant(UUID.fromString(it)) }
        val language = MessageLanguage.forCountry(tenant?.country)
        val token = tokenService.issue(invitation.guestId, invitation.eventId, tenantId)
        val ticket =
            if (event?.deliveryMode == DeliveryMode.DIRECT_TICKET) {
                if (!rsvpService.issueDirectTicket(guest)) {
                    fail(invitation, "No ticket could be issued: the event is full or the guest's data was erased")
                    return
                }
                ticketNotices.build(guest, event, tenantId, recipient)
            } else {
                null
            }

        eventPublisher.publishEvent(
            GuestInvitedEvent(
                invitationId = invitationId,
                tenantId = tenantId,
                eventId = invitation.eventId,
                channel = channelName,
                recipient = recipient,
                recipientName = "${guest.firstName} ${guest.lastName}",
                eventName = event?.name ?: "your event",
                eventWhen = event?.startDateTime?.let { MessageLanguage.formatEventStart(it, event.timezone, language) },
                eventLocation = event?.location,
                organizerName = tenant?.displayName ?: "Your organizer",
                primaryColor = tenant?.primaryColor ?: DEFAULT_COLOR,
                logoUrl = tenant?.logoUrl,
                invitationUrl = ticket?.ticketUrl ?: "${properties.appBaseUrl}/invitation/$token",
                language = language,
                ticket = ticket,
                interactive =
                    event?.deliveryMode == DeliveryMode.INTERACTIVE &&
                        channelName == GuestInvitedEvent.CHANNEL_WHATSAPP &&
                        allowanceGate.interactiveCovered(invitation.eventId),
            ),
        )
    }

    private fun fail(
        invitation: Invitation,
        error: String?,
    ) {
        invitation.status = InvitationStatus.FAILED
        invitation.lastError = error
        invitations.save(invitation)
    }

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
        val E164 = Regex("^\\+[1-9]\\d{6,14}$")
    }
}
