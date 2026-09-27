package com.jiku.invitation.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.shared.RandomCode
import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import com.jiku.shared.UsageAllowanceGate
import com.jiku.shared.VerificationGate
import com.jiku.tenant.TenantModuleApi
import com.jiku.ticket.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * The open invitation (JIKU-184, ADR 106): an event shared in groups without a
 * guest list. Each person answers once per number, and may change their answer.
 *
 * - A "yes" takes its places (the person and their companions) under the
 *   event's capacity, all at once, creates a guest holding a QR ticket, and
 *   counts those people against the guest tier. "Maybe" and "no" take nothing.
 * - Changing a "yes" gives back or takes the difference; a tier commitment is
 *   never given back, so people counted once are never counted again.
 * - The organizer can remove a person: their places are freed and the number
 *   can no longer answer.
 *
 * Public calls are reached by the invitation's code; [route] binds the tenant
 * the code belongs to before anything else is read.
 */
@Service
class OpenInvitationService(
    private val openInvitations: OpenInvitationRepository,
    private val responses: OpenResponseRepository,
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val tenantAccessGate: TenantAccessGate,
    private val ticketing: TicketingModuleApi,
    private val verification: VerificationGate,
    private val allowanceGate: UsageAllowanceGate,
    private val invitationTokens: InvitationTokenService,
    private val properties: OpenInvitationProperties,
) {
    /** Runs [block] under the tenant of [code], with its event id; 404 for an unknown code or a suspended organization. */
    fun <T> route(
        code: String,
        block: (UUID) -> T,
    ): T {
        val route =
            openInvitations.route(code.trim().uppercase())?.takeUnless { tenantAccessGate.isSuspended(it.tenantId) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This invitation was not found")
        return TenantContext.withTenant(route.tenantId) { block(route.eventId) }
    }

    @Transactional(readOnly = true)
    fun settings(
        eventId: UUID,
        now: Instant = Instant.now(),
    ): OpenInvitationView? {
        val event = loadEvent(eventId)
        return openInvitations.findByEventId(eventId)?.let { organizerView(it, event, now) }
    }

    @Transactional
    fun update(
        eventId: UUID,
        request: OpenInvitationSettingsRequest,
        now: Instant = Instant.now(),
    ): OpenInvitationView {
        val event = loadEvent(eventId)
        val companions = request.maxCompanions ?: properties.defaultCompanions.coerceAtMost(properties.maxCompanions)
        if (companions > properties.maxCompanions) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At most ${properties.maxCompanions} companions per answer")
        }
        val invitation =
            (openInvitations.findByEventId(eventId) ?: OpenInvitation(eventId = eventId, code = newCode(), maxCompanions = companions))
                .apply {
                    enabled = request.enabled
                    welcomeMessage = request.welcomeMessage?.trim()?.takeIf { it.isNotEmpty() }
                    maxCompanions = companions
                    closesAt = request.closesAt
                    request.notifyOnCancel?.let { notifyOnCancel = it }
                }
        return organizerView(openInvitations.save(invitation), event, now)
    }

    @Transactional(readOnly = true)
    fun responses(eventId: UUID): List<OrganizerOpenResponseView> {
        loadEvent(eventId)
        return responses.findByEventIdOrderByUpdatedAtDesc(eventId).filter { it.removedAt == null }.map { it.toOrganizerView() }
    }

    /** The organizer takes a person off the list: their places are freed and the number can no longer answer. */
    @Transactional
    fun remove(
        eventId: UUID,
        responseId: UUID,
        now: Instant = Instant.now(),
    ) {
        loadEvent(eventId)
        val response =
            responses.findByIdAndEventId(responseId, eventId)?.takeIf { it.removedAt == null }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Answer not found")
        releaseYes(response)
        response.answer = OpenAnswer.NO
        response.removedAt = now
        response.updatedAt = now
        responses.save(response)
    }

    @Transactional(readOnly = true)
    fun publicView(
        eventId: UUID,
        now: Instant = Instant.now(),
    ): PublicOpenInvitationView {
        val invitation = openInvitations.findByEventId(eventId) ?: throw notFound()
        val event = events.findEvent(eventId) ?: throw notFound()
        if (event.status == EventInfo.STATUS_DRAFT) throw notFound()
        val tenant = tenants.findTenant(UUID.fromString(event.tenantId))
        val closed = closedReason(invitation, event, now)
        return PublicOpenInvitationView(
            code = invitation.code,
            eventName = event.name,
            eventStart = event.startDateTime,
            eventEnd = event.endDateTime,
            eventTimezone = event.timezone,
            eventLocation = event.location,
            welcomeMessage = invitation.welcomeMessage,
            organizerName = event.brand.name ?: tenant?.displayName ?: event.name,
            logoUrl = event.brand.logoUrl ?: tenant?.logoUrl,
            primaryColor = event.brand.primaryColor ?: tenant?.primaryColor,
            organizerVerification = verification.verifiedKind(),
            maxCompanions = invitation.maxCompanions,
            closesAt = invitation.closesAt,
            accepting = closed == null,
            closedReason = closed,
            whatsappNumber = whatsappNumber(),
        )
    }

    /**
     * Records [request] as the answer of its number, or changes the answer that
     * number already gave. Refused when the invitation takes no answer, when
     * the number was removed by the organizer, when the places are gone, or
     * when the organizer's guest tier is used up.
     */
    @Transactional
    fun respond(
        eventId: UUID,
        request: OpenResponseRequest,
        channel: OpenResponseChannel,
        now: Instant = Instant.now(),
    ): OpenResponseView {
        val invitation = openInvitations.findByEventId(eventId) ?: throw notFound()
        val event = events.findEvent(eventId) ?: throw notFound()
        closedReason(invitation, event, now)?.takeUnless { it == OpenClosedReason.FULL }?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This invitation takes no answer ($it)")
        }
        val phone = phoneDigits(request.phone)
        val name = request.name.trim()
        val companions = if (request.answer == OpenAnswer.YES) request.companions else 0
        if (companions > invitation.maxCompanions) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "At most ${invitation.maxCompanions} companions")
        }
        val existing = responses.findByEventIdAndPhone(eventId, phone)
        if (existing?.removedAt != null) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "The organizer removed this number from the invitation")
        }
        val previousHeads = if (existing?.guestId != null) existing.heads else 0
        if (existing != null && request.answer != OpenAnswer.YES) releaseYes(existing)
        val response =
            existing ?: OpenResponse(
                eventId = eventId,
                phone = phone,
                name = name,
                answer = request.answer,
                companions = companions,
                channel = channel,
                createdAt = now,
            )
        response.name = name
        response.answer = request.answer
        response.companions = companions
        response.channel = channel
        response.updatedAt = now
        if (request.answer == OpenAnswer.YES) takePlaces(response, previousHeads, 1 + companions)
        val saved = responses.save(response)
        val ticketToken =
            saved.guestId?.takeIf { saved.answer == OpenAnswer.YES }?.let {
                invitationTokens.issue(it, eventId, event.tenantId)
            }
        return OpenResponseView(answer = saved.answer, companions = saved.companions, ticketToken = ticketToken)
    }

    /**
     * Gives [response] the places of [heads] people, the guest and the ticket:
     * the first "yes" takes them all, a changed "yes" takes or gives back the
     * difference. The tier counts only people never counted before.
     */
    private fun takePlaces(
        response: OpenResponse,
        previousHeads: Int,
        heads: Int,
    ) {
        val eventId = response.eventId
        val delta = heads - previousHeads
        val extra = (heads - response.committedHeads).coerceAtLeast(0)
        if (extra > 0) enforceAllowance(eventId, extra)
        if (delta > 0 && !events.reserveAttendanceSlots(eventId, null, delta)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Not enough places left")
        }
        if (delta < 0) events.releaseAttendanceSlots(eventId, null, -delta)
        if (extra > 0) {
            allowanceGate.recordCommitment(eventId, extra.toLong())
            response.committedHeads += extra
        }
        val guest =
            response.guestId?.let { guests.findById(it).orElse(null) }
                ?: Guest(eventId = eventId, firstName = "", lastName = "", phoneNumber = "+${response.phone}").apply {
                    excludedFromInvitations = true
                    origin = GuestOrigin.OPEN
                }
        guest.firstName = response.name.substringBefore(' ')
        guest.lastName = response.name.substringAfter(' ', "").trim()
        guest.companions = heads - 1
        val newTicket = guest.rsvpStatus != RsvpStatus.CONFIRMED
        guest.rsvpStatus = RsvpStatus.CONFIRMED
        val saved = guests.save(guest)
        if (newTicket) ticketing.issueTicket(eventId, requireNotNull(saved.id))
        response.guestId = saved.id
    }

    /** Frees the places and cancels the ticket of a "yes"; nothing for any other answer. */
    private fun releaseYes(response: OpenResponse) {
        val guestId = response.guestId ?: return
        if (response.answer != OpenAnswer.YES) return
        val guest = guests.findById(guestId).orElse(null) ?: return
        if (guest.rsvpStatus != RsvpStatus.CONFIRMED) return
        events.releaseAttendanceSlots(response.eventId, null, response.heads)
        ticketing.cancelByGuest(guestId)
        guest.rsvpStatus = RsvpStatus.DECLINED
        guests.save(guest)
    }

    /**
     * The same ceiling as an invitation send: people invited by the organizer
     * and people counted by open answers share the event's guest allowance.
     */
    private fun enforceAllowance(
        eventId: UUID,
        extra: Int,
    ) {
        val committed = committedGuests(eventId)
        if (committed + extra > allowanceGate.allowanceCeiling(eventId, committed)) {
            throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "The organizer's guest allowance is used up")
        }
    }

    private fun committedGuests(eventId: UUID): Long =
        invitations
            .findByEventId(eventId)
            .map { it.guestId }
            .toSet()
            .size + responses.committedHeads(eventId)

    private fun organizerView(
        invitation: OpenInvitation,
        event: EventInfo,
        now: Instant,
    ): OpenInvitationView {
        val all = responses.findByEventIdOrderByUpdatedAtDesc(invitation.eventId).filter { it.removedAt == null }
        val closed = closedReason(invitation, event, now)
        return OpenInvitationView(
            eventId = invitation.eventId,
            code = invitation.code,
            enabled = invitation.enabled,
            welcomeMessage = invitation.welcomeMessage,
            maxCompanions = invitation.maxCompanions,
            maxCompanionsAllowed = properties.maxCompanions,
            closesAt = invitation.closesAt,
            accepting = closed == null,
            closedReason = closed,
            remainingPlaces = events.remainingAttendance(invitation.eventId),
            counts =
                OpenInvitationCounts(
                    yes = all.count { it.answer == OpenAnswer.YES },
                    maybe = all.count { it.answer == OpenAnswer.MAYBE },
                    no = all.count { it.answer == OpenAnswer.NO },
                    expected = all.sumOf { it.heads },
                ),
            notifyOnCancel = invitation.notifyOnCancel,
            cancelNoticeIncluded = allowanceGate.paidTier(invitation.eventId),
            whatsappNumber = whatsappNumber(),
        )
    }

    private fun closedReason(
        invitation: OpenInvitation,
        event: EventInfo,
        now: Instant,
    ): OpenClosedReason? =
        when {
            event.status == EventInfo.STATUS_CANCELLED -> OpenClosedReason.CANCELLED
            event.status != EventInfo.STATUS_PUBLISHED -> OpenClosedReason.NOT_PUBLISHED
            (event.endDateTime ?: event.startDateTime)?.isBefore(now) == true -> OpenClosedReason.ENDED
            !invitation.enabled -> OpenClosedReason.DISABLED
            invitation.closesAt?.isBefore(now) == true -> OpenClosedReason.CLOSED
            events.remainingAttendance(event.id)?.let { it <= 0 } == true -> OpenClosedReason.FULL
            else -> null
        }

    private fun OpenResponse.toOrganizerView(): OrganizerOpenResponseView =
        OrganizerOpenResponseView(
            id = requireNotNull(id),
            name = name,
            phone = if (erased) "" else "+$phone",
            answer = answer,
            companions = companions,
            channel = channel,
            updatedAt = updatedAt,
        )

    private fun loadEvent(eventId: UUID): EventInfo =
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    private fun whatsappNumber(): String? = properties.whatsappNumber.filter { it.isDigit() }.takeIf { it.isNotEmpty() }

    private fun newCode(): String {
        repeat(CODE_ATTEMPTS) {
            val candidate = RandomCode.generate(CODE_LENGTH)
            if (!openInvitations.existsByCode(candidate)) return candidate
        }
        throw IllegalStateException("Could not draw a free open invitation code")
    }

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "This invitation was not found")

    companion object {
        private const val CODE_LENGTH = 8
        private const val CODE_ATTEMPTS = 5
        private const val MIN_PHONE_DIGITS = 8
        private const val MAX_PHONE_DIGITS = 15

        /** A number as WhatsApp reports a sender: digits only, with the country code. */
        fun phoneDigits(phone: String): String {
            val digits = phone.filter { it.isDigit() }.removePrefix("00")
            if (digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a phone number with its country code")
            }
            return digits
        }
    }
}
