package com.jiku.invitation.internal

import com.jiku.event.EventModuleApi
import com.jiku.event.InvitationChannel
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Marks invitations PENDING for an event's guests (the synchronous, committed part
 * of "send"), and exposes per-guest delivery status. The actual delivery runs
 * asynchronously via [InvitationDispatcher].
 */
@Service
class InvitationSendingService(
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
) {
    @Transactional
    fun queue(
        eventId: UUID,
        onlyUnsent: Boolean,
    ): SendInvitationsResult {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        var queued = 0
        for (guest in guests.findByEventId(eventId)) {
            if (guest.email == null) {
                continue
            }
            val guestId = requireNotNull(guest.id)
            val existing = invitations.findByGuestIdAndChannel(guestId, InvitationChannel.EMAIL)
            if (onlyUnsent && existing?.status == InvitationStatus.SENT) {
                continue
            }
            val invitation = existing ?: Invitation(eventId, guestId, InvitationChannel.EMAIL)
            invitation.status = InvitationStatus.PENDING
            invitation.lastError = null
            invitations.save(invitation)
            queued++
        }
        return SendInvitationsResult(queued)
    }

    @Transactional(readOnly = true)
    fun statuses(eventId: UUID): List<InvitationStatusResponse> =
        invitations.findByEventId(eventId).map {
            InvitationStatusResponse(it.guestId, it.channel.name, it.status.name, it.attempts, it.sentAt)
        }
}
