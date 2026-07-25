package com.jiku.invitation.internal

import com.jiku.event.EventModuleApi
import com.jiku.event.InvitationChannel
import com.jiku.shared.UsageAllowanceGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Marks invitations PENDING for an event's guests (the synchronous, committed part
 * of "send"), and exposes per-guest delivery status. The actual delivery runs
 * asynchronously via [InvitationDispatcher].
 *
 * Sending is the paywall enforcement point (JIKU-34): a batch that would invite
 * more distinct guests than the event's unlocked allowance is blocked in full
 * (never partially) before anything is committed. The backend is the authority —
 * a client that skips any pre-check still hits this.
 */
@Service
class InvitationSendingService(
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val allowanceGate: UsageAllowanceGate,
) {
    @Transactional
    fun queue(
        eventId: UUID,
        channels: Set<InvitationChannel>,
        onlyUnsent: Boolean,
    ): SendInvitationsResult {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        // First pass: resolve the invitations this send would create/re-queue,
        // without persisting, so the paywall can veto the whole batch.
        val toQueue = mutableListOf<Invitation>()
        for (guest in guests.findByEventId(eventId)) {
            if (guest.excludedFromInvitations) {
                continue
            }
            val guestId = requireNotNull(guest.id)
            for (channel in channels) {
                val eligible =
                    when (channel) {
                        InvitationChannel.EMAIL -> guest.email != null
                        InvitationChannel.WHATSAPP -> guest.phoneNumber != null
                    }
                if (!eligible) {
                    continue
                }
                val existing = invitations.findByGuestIdAndChannel(guestId, channel)
                if (onlyUnsent && existing?.status == InvitationStatus.SENT) {
                    continue
                }
                toQueue += (existing ?: Invitation(eventId, guestId, channel))
            }
        }

        enforceAllowance(eventId, toQueue.map { it.guestId }.toSet())

        for (invitation in toQueue) {
            invitation.status = InvitationStatus.PENDING
            invitation.lastError = null
            invitations.save(invitation)
        }
        return SendInvitationsResult(toQueue.size)
    }

    /**
     * Blocks the send if the newly-invited distinct guests would push the event
     * past its unlocked allowance. Guests already committed to being invited
     * (a PENDING or SENT invitation) are never re-charged, so an organizer already
     * over their limit (e.g. after a tier change) can still re-send to existing
     * guests — only *new* guests beyond the ceiling are blocked.
     */
    private fun enforceAllowance(
        eventId: UUID,
        batchGuestIds: Set<UUID>,
    ) {
        val ceiling = allowanceGate.allowanceCeiling(eventId)
        val committed =
            invitations
                .findByEventId(eventId)
                .filter { it.status != InvitationStatus.FAILED }
                .map { it.guestId }
                .toSet()
        val projected = committed + batchGuestIds
        val addsNewGuests = projected.size > committed.size
        if (addsNewGuests && projected.size > ceiling) {
            val remaining = (ceiling - committed.size).coerceAtLeast(0)
            throw ResponseStatusException(
                HttpStatus.PAYMENT_REQUIRED,
                "This event's guest allowance ($ceiling) would be exceeded. " +
                    "You can invite $remaining more guest(s); purchase additional capacity to send to the rest.",
            )
        }
    }

    @Transactional(readOnly = true)
    fun statuses(eventId: UUID): List<InvitationStatusResponse> =
        invitations.findByEventId(eventId).map {
            InvitationStatusResponse(it.guestId, it.channel.name, it.status.name, it.attempts, it.sentAt)
        }
}
