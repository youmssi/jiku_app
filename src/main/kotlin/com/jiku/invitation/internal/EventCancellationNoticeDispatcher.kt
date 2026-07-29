package com.jiku.invitation.internal

import com.jiku.event.EventModuleApi
import com.jiku.event.InvitationChannel
import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.EventCancelledEvent
import com.jiku.shared.GuestInvitedEvent
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Tells every invited guest their event was cancelled. Listens for the committed
 * cancellation (never for one that might still roll back), then publishes one
 * [EventCancellationNotice] per successfully sent invitation — the channels that
 * actually reached each guest, regardless of their RSVP status. Delivery, retry
 * and auditing are the notification module's job.
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
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Async("invitationExecutor")
    @TransactionalEventListener
    fun onEventCancelled(cancelled: EventCancelledEvent) {
        val event = events.findEvent(cancelled.eventId) ?: return
        val tenant = tenants.findTenant(UUID.fromString(cancelled.tenantId))
        val guestsById = guests.findByEventId(cancelled.eventId).associateBy { requireNotNull(it.id) }

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
                eventPublisher.publishEvent(
                    EventCancellationNotice(
                        invitationId = requireNotNull(invitation.id),
                        tenantId = cancelled.tenantId,
                        eventId = cancelled.eventId,
                        channel = channelName,
                        recipient = recipient,
                        recipientName = "${guest.firstName} ${guest.lastName}",
                        eventName = event.name,
                        eventWhen = event.startDateTime?.let { formatWhen(it, event.timezone) },
                        eventLocation = event.location,
                        organizerName = tenant?.displayName ?: "Your organizer",
                        primaryColor = tenant?.primaryColor ?: DEFAULT_COLOR,
                        logoUrl = tenant?.logoUrl,
                    ),
                )
            }
    }

    private fun formatWhen(
        instant: Instant,
        timezone: String,
    ): String = WHEN_FORMAT.withZone(ZoneId.of(timezone)).format(instant)

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm")
    }
}
