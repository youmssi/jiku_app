package com.jiku.invitation.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import com.jiku.ticket.TicketInfo
import com.jiku.ticket.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Guest RSVP use cases. Confirming atomically reserves a capacity slot and issues
 * a ticket; declining frees the slot and cancels the ticket. The tenant context is
 * already bound (from the invitation token) by the controller before these run.
 */
@Service
class RsvpService(
    private val guests: GuestRepository,
    private val invitations: InvitationRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val ticketing: TicketingModuleApi,
) {
    @Transactional(readOnly = true)
    fun view(guestId: UUID): RsvpView = buildView(loadGuest(guestId))

    @Transactional
    fun confirm(
        guestId: UUID,
        eventId: UUID,
    ): RsvpView {
        requireNotCancelled(eventId)
        val guest = loadGuest(guestId)
        if (guest.rsvpStatus != RsvpStatus.CONFIRMED) {
            // La catégorie de l'invité, s'il en a une : les deux plafonds sont
            // alors vérifiés ensemble (JIKU-93).
            if (!events.reserveAttendanceSlot(eventId, guest.ticketTypeId)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "This event is full")
            }
            guest.rsvpStatus = RsvpStatus.CONFIRMED
            guests.save(guest)
            ticketing.issueTicket(eventId, guestId, guest.ticketTypeId)
        }
        return buildView(guest)
    }

    @Transactional
    fun decline(
        guestId: UUID,
        eventId: UUID,
    ): RsvpView {
        requireNotCancelled(eventId)
        val guest = loadGuest(guestId)
        if (guest.rsvpStatus == RsvpStatus.CONFIRMED) {
            events.releaseAttendanceSlot(eventId, guest.ticketTypeId)
            ticketing.cancelByGuest(guestId)
        }
        guest.rsvpStatus = RsvpStatus.DECLINED
        guests.save(guest)
        return buildView(guest)
    }

    /**
     * Hands a confirmed guest's place to someone else (JIKU-64).
     *
     * The recipient becomes a new guest of the event, already confirmed, holding a
     * freshly issued ticket; the sender's ticket is cancelled — so their QR code
     * stops validating at the door — and their row is kept as `TRANSFERRED` with a
     * link to the recipient, which is what lets a validator reconstruct who a place
     * originally belonged to if it is ever disputed.
     *
     * Capacity is deliberately untouched: the place is moved, not released and
     * re-taken, so a transfer can never lose the slot to someone else mid-way and
     * can never be used to slip past a full event. For the same reason the
     * recipient's invitation does not re-charge the tenant's guest allowance —
     * that seat was already paid for when the sender was invited.
     */
    @Transactional
    fun transfer(
        guestId: UUID,
        eventId: UUID,
        request: TransferTicketRequest,
    ): RsvpView {
        requireNotCancelled(eventId)
        val email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        val phone = request.phoneNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (email == null && phone == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Give the recipient an email address or a phone number so they can receive their invitation",
            )
        }

        val event =
            events.findEvent(eventId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        if (!event.transferAllowed) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Transfers are closed for this event")
        }
        event.transferDeadline?.let { deadline ->
            if (Instant.now().isAfter(deadline)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "The transfer deadline for this event has passed")
            }
        }

        val sender = loadGuest(guestId)
        if (sender.personalDataErased) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This invitation is no longer available")
        }
        if (sender.rsvpStatus != RsvpStatus.CONFIRMED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only a confirmed guest can hand their place to someone else",
            )
        }
        val ticket =
            ticketing.findByGuest(guestId)
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "There is no ticket to transfer")
        if (ticket.status == TicketInfo.STATUS_CHECKED_IN) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This ticket has already been used at the entrance")
        }

        val recipient =
            Guest(
                eventId = eventId,
                firstName = request.firstName.trim(),
                lastName = request.lastName.trim(),
                email = email,
                phoneNumber = phone,
            )
        recipient.rsvpStatus = RsvpStatus.CONFIRMED
        recipient.transferredFromGuestId = guestId
        guests.save(recipient)
        val recipientId = requireNotNull(recipient.id)

        ticketing.cancelByGuest(guestId)
        ticketing.issueTicket(eventId, recipientId)

        sender.rsvpStatus = RsvpStatus.TRANSFERRED
        sender.transferredToGuestId = recipientId
        sender.transferredAt = Instant.now()
        guests.save(sender)

        queueRecipientInvitation(eventId, recipientId, email, phone)
        return buildView(sender)
    }

    /**
     * Queues the recipient's own invitation on every channel their contact details
     * support. Delivery itself runs after commit, driven by the same dispatcher the
     * organizer's send uses — the recipient's link resolves straight to their
     * ticket, since they are already confirmed.
     */
    private fun queueRecipientInvitation(
        eventId: UUID,
        recipientId: UUID,
        email: String?,
        phone: String?,
    ) {
        val channels =
            listOfNotNull(
                email?.let { InvitationChannel.EMAIL },
                phone?.let { InvitationChannel.WHATSAPP },
            )
        channels.forEach { channel -> invitations.save(Invitation(eventId, recipientId, channel)) }
    }

    private fun loadGuest(guestId: UUID): Guest =
        guests.findById(guestId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found")
        }

    /** A cancelled event accepts no further RSVP changes (JIKU-14B). */
    private fun requireNotCancelled(eventId: UUID) {
        if (events.findEvent(eventId)?.status == EventInfo.STATUS_CANCELLED) {
            throw ResponseStatusException(HttpStatus.GONE, "This event has been cancelled")
        }
    }

    private fun buildView(guest: Guest): RsvpView {
        val event = events.findEvent(guest.eventId)
        val tenant = TenantContext.get()?.let { tenants.findTenant(UUID.fromString(it)) }
        val ticket =
            if (guest.rsvpStatus == RsvpStatus.CONFIRMED) ticketing.findByGuest(requireNotNull(guest.id)) else null
        return RsvpView(
            eventName = event?.name ?: "Event",
            eventWhen = event?.startDateTime?.let { formatWhen(it, event.timezone) },
            eventLocation = event?.location,
            organizerName = tenant?.displayName ?: "Your organizer",
            primaryColor = tenant?.primaryColor ?: "#1E293B",
            logoUrl = tenant?.logoUrl,
            guestName = "${guest.firstName} ${guest.lastName}",
            status = guest.rsvpStatus.name,
            ticketCode = ticket?.ticketCode,
            eventStatus = event?.status,
            erased = guest.personalDataErased,
            transferAllowed = canTransfer(guest, event, ticket),
            transferDeadline = event?.transferDeadline,
            transferredTo = guest.transferredToGuestId?.let { recipientName(it) },
        )
    }

    /**
     * Whether the transfer affordance should be offered. Mirrors the checks
     * [transfer] enforces, so the UI never shows a control that the endpoint would
     * refuse — and the endpoint never trusts that the UI got it right.
     */
    private fun canTransfer(
        guest: Guest,
        event: EventInfo?,
        ticket: TicketInfo?,
    ): Boolean {
        if (event == null || !event.transferAllowed || event.status == EventInfo.STATUS_CANCELLED) return false
        if (guest.rsvpStatus != RsvpStatus.CONFIRMED || guest.personalDataErased) return false
        if (ticket == null || ticket.status == TicketInfo.STATUS_CHECKED_IN) return false
        return event.transferDeadline?.isAfter(Instant.now()) ?: true
    }

    private fun recipientName(recipientId: UUID): String? =
        guests.findById(recipientId).orElse(null)?.let { "${it.firstName} ${it.lastName}" }

    private fun formatWhen(
        instant: Instant,
        timezone: String,
    ): String = WHEN_FORMAT.withZone(ZoneId.of(timezone)).format(instant)

    private companion object {
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm")
    }
}
