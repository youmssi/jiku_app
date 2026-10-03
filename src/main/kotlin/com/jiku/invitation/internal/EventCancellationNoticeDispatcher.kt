package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.EventCancelledEvent
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.MessageLanguage
import com.jiku.shared.UsageAllowanceGate
import com.jiku.shared.async.Executors
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * Tells every invited guest their event was cancelled. Listens for the committed
 * cancellation (never for one that might still roll back), then publishes one
 * [EventCancellationNotice] per successfully sent invitation — the channels that
 * actually reached each guest, regardless of their RSVP status. Delivery, retry
 * and auditing are the notification module's job.
 *
 * The people who answered an open invitation "yes" or "maybe" (JIKU-187) are
 * told by WhatsApp, the number they answered with: always on a paid tier, and
 * on the free tier when the organizer chose it in the invitation's settings.
 * A number also on the guest list is told once, through its invitation.
 *
 * Runs on the tenant-aware executor so tenant-scoped reads resolve correctly off
 * the request thread.
 */
@Component
class EventCancellationNoticeDispatcher(
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val openInvitations: OpenInvitationRepository,
    private val openResponses: OpenResponseRepository,
    private val allowanceGate: UsageAllowanceGate,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Async(Executors.BULK)
    @TransactionalEventListener
    fun onEventCancelled(cancelled: EventCancelledEvent) {
        // Cancelling without notifying guests is an organizer choice: tickets are
        // already invalidated, and no cancellation notice leaves this point.
        if (!cancelled.notifyGuests) {
            return
        }
        val event = events.findEvent(cancelled.eventId) ?: return
        val tenant = tenants.findTenant(UUID.fromString(cancelled.tenantId))
        val language = MessageLanguage.forCountry(tenant?.country)
        val guestsById = guests.findByEventId(cancelled.eventId).associateBy { requireNotNull(it.id) }

        val notice =
            EventCancellationNotice(
                invitationId = cancelled.eventId,
                tenantId = cancelled.tenantId,
                eventId = cancelled.eventId,
                channel = GuestInvitedEvent.CHANNEL_WHATSAPP,
                recipient = "",
                recipientName = "",
                eventName = event.name,
                eventWhen = event.startDateTime?.let { MessageLanguage.formatEventStart(it, event.timezone, language) },
                eventLocation = event.location,
                organizerName = event.brand.name ?: tenant?.displayName ?: "Your organizer",
                primaryColor = event.brand.primaryColor ?: tenant?.primaryColor ?: DEFAULT_COLOR,
                logoUrl = event.brand.logoUrl ?: tenant?.logoUrl,
                language = language,
            )
        val toldPhones = mutableSetOf<String>()

        invitations
            .findByEventId(cancelled.eventId)
            .filter { it.status == InvitationStatus.SENT }
            .forEach { invitation ->
                val guest = guestsById[invitation.guestId] ?: return@forEach
                val (channelName, recipient) =
                    when (invitation.channel) {
                        InvitationChannel.EMAIL -> GuestInvitedEvent.CHANNEL_EMAIL to guest.email
                        InvitationChannel.WHATSAPP -> GuestInvitedEvent.CHANNEL_WHATSAPP to guest.phoneNumber
                    }
                if (recipient == null) {
                    return@forEach
                }
                if (channelName == GuestInvitedEvent.CHANNEL_WHATSAPP) toldPhones += recipient.filter { it.isDigit() }
                eventPublisher.publishEvent(
                    notice.copy(
                        invitationId = requireNotNull(invitation.id),
                        channel = channelName,
                        recipient = recipient,
                        recipientName = "${guest.firstName} ${guest.lastName}",
                    ),
                )
            }

        val open = openInvitations.findByEventId(cancelled.eventId) ?: return
        if (!open.notifyOnCancel && !allowanceGate.paidTier(cancelled.eventId)) {
            return
        }
        openResponses
            .findByEventIdOrderByUpdatedAtDesc(cancelled.eventId)
            .filter { it.removedAt == null && !it.erased && it.answer != OpenAnswer.NO }
            .filter { toldPhones.add(it.phone) }
            .forEach { response ->
                eventPublisher.publishEvent(
                    notice.copy(
                        invitationId = requireNotNull(response.id),
                        recipient = "+${response.phone}",
                        recipientName = response.name,
                    ),
                )
            }
    }

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
    }
}
