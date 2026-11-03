package com.jiku.invitation.internal

import com.jiku.catalog.DeliveryMode
import com.jiku.catalog.InvitationChannel
import com.jiku.shared.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * "Send from my WhatsApp" (JIKU-281): Jikū prepares each guest's personal link,
 * and the organizer sends it from their own WhatsApp, one chat at a time. The
 * message comes from someone the guest knows, and costs nothing at Meta.
 *
 * Preparing counts the guests against the event's allowance exactly as a send
 * does, all or nothing, so a link is never handed out past what was paid for.
 * Opening a guest's chat marks the invitation sent; in direct-ticket mode it
 * issues the ticket the link then shows.
 */
@Service
class OrganizerWhatsAppService(
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val sending: InvitationSendingService,
    private val inviteCodes: GuestInviteCodes,
    private val rsvpService: RsvpService,
    private val properties: InvitationSendProperties,
) {
    /**
     * Every guest with a WhatsApp number, with their personal link, prepared for the
     * organizer. A guest Jikū is already delivering to, or delivered to, is listed
     * as such and not prepared again.
     */
    @Transactional
    fun prepare(eventId: UUID): List<OrganizerWhatsAppGuest> {
        sending.openForSending(eventId)
        val tenantId = TenantContext.get().orEmpty()
        val existing = invitations.findByEventId(eventId)
        val byGuest = existing.groupBy { it.guestId }
        val reachable =
            guests
                .findByEventId(eventId)
                .filter { !it.excludedFromInvitations && !it.personalDataErased }
                .filter { it.phoneNumber?.let(InvitationDispatchWorker.E164::matches) == true }
        val toPrepare = reachable.filter { guest -> byGuest[guest.id].orEmpty().none { it.inJikuHands() } }

        sending.commitGuests(eventId, byGuest.keys, toPrepare.mapNotNull { it.id }.toSet())
        for (guest in toPrepare) {
            val guestId = requireNotNull(guest.id)
            val invitation =
                byGuest[guestId]?.firstOrNull { it.channel == InvitationChannel.WHATSAPP }
                    ?: Invitation(eventId, guestId, InvitationChannel.WHATSAPP)
            if (invitation.status != InvitationStatus.SENT) {
                invitation.status = InvitationStatus.PREPARED
                invitation.lastError = null
                invitations.save(invitation)
            }
        }

        val prepared = toPrepare.mapNotNull { it.id }.toSet()
        return reachable.map { guest ->
            val guestId = requireNotNull(guest.id)
            val sent = byGuest[guestId].orEmpty().firstOrNull { it.status == InvitationStatus.SENT }
            OrganizerWhatsAppGuest(
                guestId = guestId,
                firstName = guest.firstName,
                lastName = guest.lastName,
                phone = requireNotNull(guest.phoneNumber),
                link = if (guestId in prepared) link(guest, tenantId) else null,
                sentAt = sent?.sentAt,
                sentByOrganizer = sent?.sentByOrganizer ?: false,
            )
        }
    }

    /**
     * The organizer opened the guest's chat with the personal link: the invitation
     * counts as sent, and in direct-ticket mode the guest's ticket is issued. Calling
     * it again changes nothing.
     */
    @Transactional
    fun markSent(
        eventId: UUID,
        guestId: UUID,
    ): OrganizerWhatsAppGuest {
        val event = sending.openForSending(eventId)
        val guest =
            guests.findById(guestId).orElse(null)?.takeIf { it.eventId == eventId }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Guest not found")
        val invitation =
            invitations
                .findByGuestIdAndChannel(guestId, InvitationChannel.WHATSAPP)
                ?.takeIf { it.status == InvitationStatus.PREPARED || it.sentByOrganizer }
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Prepare the guest's link before sending it")
        if (invitation.status != InvitationStatus.SENT) {
            if (event.deliveryMode == DeliveryMode.DIRECT_TICKET && !rsvpService.issueDirectTicket(guest)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "No ticket could be issued: the event is full")
            }
            val now = Instant.now()
            invitation.status = InvitationStatus.SENT
            invitation.sentByOrganizer = true
            invitation.sentAt = now
            invitation.dispatchedAt = now
            invitations.save(invitation)
        }
        return OrganizerWhatsAppGuest(
            guestId = guestId,
            firstName = guest.firstName,
            lastName = guest.lastName,
            phone = guest.phoneNumber.orEmpty(),
            link = link(guest, TenantContext.get().orEmpty()),
            sentAt = invitation.sentAt,
            sentByOrganizer = true,
        )
    }

    private fun link(
        guest: Guest,
        tenantId: String,
    ): String = "${properties.appBaseUrl}/v/${inviteCodes.linkKey(guest, tenantId)}"

    /** Jikū is delivering this invitation, or has delivered it: the organizer has nothing to send. */
    private fun Invitation.inJikuHands(): Boolean =
        !sentByOrganizer && status in setOf(InvitationStatus.PENDING, InvitationStatus.QUEUED, InvitationStatus.SENT)
}

/** A guest the organizer can invite from their own WhatsApp; [link] is null when Jikū handles the guest. */
data class OrganizerWhatsAppGuest(
    val guestId: UUID,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val link: String?,
    val sentAt: Instant?,
    val sentByOrganizer: Boolean,
)
