package com.jiku.invitation.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
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
        val event = events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        requireOpenForSending(event)
        val unsupported = channels - event.invitationChannels
        if (unsupported.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Channels not enabled for this event: ${unsupported.joinToString { it.name }}. " +
                    "Publish the event with these channels to send on them.",
            )
        }

        // Load the event's existing invitations once, not once per (guest, channel):
        // a send to a 1 000-guest event must not issue thousands of point queries.
        val existingByGuestAndChannel =
            invitations.findByEventId(eventId).associateBy { it.guestId to it.channel }
        // Un invité déjà pris en charge — que la livraison ait réussi (PENDING/SENT),
        // soit en attente de rejeu (QUEUED) ou ait échoué (FAILED) — est déjà compté
        // dans le budget : on ne le refacture jamais à chaque envoi, sinon un numéro
        // invalide consommerait le budget gratuit à chaque tentative.
        val committed = existingByGuestAndChannel.keys.map { it.first }.toSet()

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
                val existing = existingByGuestAndChannel[guestId to channel]
                if (onlyUnsent && existing?.status == InvitationStatus.SENT) {
                    continue
                }
                toQueue += (existing ?: Invitation(eventId, guestId, channel))
            }
        }

        val batchGuestIds = toQueue.map { it.guestId }.toSet()
        enforceAllowance(eventId, committed, batchGuestIds)

        for (invitation in toQueue) {
            invitation.status = InvitationStatus.PENDING
            invitation.lastError = null
            invitations.save(invitation)
        }

        // Lock in only the guests genuinely new to this event — re-sending to
        // already-committed guests never re-charges the tenant's free budget.
        val newGuestCount = (batchGuestIds - committed).size.toLong()
        if (newGuestCount > 0) {
            allowanceGate.recordCommitment(eventId, newGuestCount)
        }
        return SendInvitationsResult(toQueue.size)
    }

    /**
     * Un événement n'accepte d'invitations que publié : un envoi sur un brouillon
     * (jamais montré aux invités) ou un événement annulé (dont les liens doivent
     * cesser de se résoudre) est refusé ici, pas dans l'UI.
     */
    private fun requireOpenForSending(event: EventInfo) {
        when (event.status) {
            EventInfo.STATUS_PUBLISHED -> Unit
            EventInfo.STATUS_CANCELLED ->
                throw ResponseStatusException(HttpStatus.GONE, "This event has been cancelled")
            else ->
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Publish the event before sending invitations",
                )
        }
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
        committed: Set<UUID>,
        batchGuestIds: Set<UUID>,
    ) {
        val ceiling = allowanceGate.allowanceCeiling(eventId, committed.size.toLong())
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
