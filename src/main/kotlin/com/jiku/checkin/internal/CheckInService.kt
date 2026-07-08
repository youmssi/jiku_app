package com.jiku.checkin.internal

import com.jiku.event.EventInfo
import com.jiku.event.EventModuleApi
import com.jiku.invitation.GuestInfo
import com.jiku.invitation.InvitationModuleApi
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import com.jiku.ticketing.CheckInOutcome
import com.jiku.ticketing.CheckInResult
import com.jiku.ticketing.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The check-in use case. Resolves a ticket (by scanned code or by a searched
 * guest), scopes it to the event being staffed, and atomically marks it checked in
 * via the ticketing module — attributing the action to the validator. Guest
 * details for the response come from the invitation module; nothing here reaches
 * into another module's internals.
 */
@Service
class CheckInService(
    private val ticketing: TicketingModuleApi,
    private val invitation: InvitationModuleApi,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
) {
    /**
     * Branding and live attendance context for the validator opening [validatorLabel]'s
     * link against [eventId]. The tenant is already bound by the caller.
     */
    fun context(
        eventId: UUID,
        validatorLabel: String,
    ): ValidatorContextResponse {
        val event = events.findEvent(eventId)
        val tenant = TenantContext.get()?.let { tenants.findTenant(UUID.fromString(it)) }
        val stats = ticketing.attendanceStats(eventId)
        return ValidatorContextResponse(
            eventName = event?.name ?: "Event",
            eventStatus = event?.status ?: "PUBLISHED",
            startDateTime = event?.startDateTime,
            timezone = event?.timezone ?: "UTC",
            eventLocation = event?.location,
            organizerName = tenant?.displayName ?: "Your organizer",
            primaryColor = tenant?.primaryColor ?: "#1E293B",
            logoUrl = tenant?.logoUrl,
            validatorLabel = validatorLabel,
            checkedIn = stats.checkedIn,
            confirmed = stats.confirmed,
        )
    }

    fun checkInByCode(
        eventId: UUID,
        ticketCode: String,
        validatorLabel: String,
    ): CheckInResponse {
        if (eventCancelled(eventId)) {
            return eventCancelledResponse()
        }
        val ticket = ticketing.findByCode(ticketCode)
        if (ticket == null || ticket.eventId != eventId) {
            return notFound()
        }
        return respond(ticketing.checkInByCode(ticketCode, validatorLabel))
    }

    fun checkInByGuest(
        eventId: UUID,
        guestId: UUID,
        validatorLabel: String,
    ): CheckInResponse {
        if (eventCancelled(eventId)) {
            return eventCancelledResponse()
        }
        val guest = invitation.findGuest(guestId)
        if (guest == null || guest.eventId != eventId) {
            return notFound()
        }
        return respond(ticketing.checkInByGuest(guestId, validatorLabel))
    }

    fun search(
        eventId: UUID,
        query: String,
    ): List<GuestMatch> {
        if (eventCancelled(eventId)) {
            throw ResponseStatusException(HttpStatus.GONE, "This event has been cancelled")
        }
        return invitation.searchGuests(eventId, query).map { guest ->
            val ticket = ticketing.findByGuest(guest.id)
            GuestMatch(
                guestId = guest.id,
                name = guest.fullName(),
                email = guest.email,
                phoneNumber = guest.phoneNumber,
                rsvpStatus = guest.rsvpStatus,
                ticketCode = ticket?.ticketCode,
                ticketStatus = ticket?.status,
                checkedInAt = ticket?.checkedInAt,
                checkedInBy = ticket?.checkedInBy,
            )
        }
    }

    fun stats(eventId: UUID): AttendanceResponse {
        val stats = ticketing.attendanceStats(eventId)
        return AttendanceResponse(checkedIn = stats.checkedIn, confirmed = stats.confirmed)
    }

    /** Full guest/ticket roster for an event, for a validator to cache offline. */
    fun roster(eventId: UUID): List<RosterEntry> {
        val ticketsByGuest = ticketing.findTicketsByEvent(eventId).associateBy { it.guestId }
        return invitation.listGuests(eventId).map { guest ->
            val ticket = ticketsByGuest[guest.id]
            RosterEntry(
                guestId = guest.id,
                name = guest.fullName(),
                email = guest.email,
                phoneNumber = guest.phoneNumber,
                rsvpStatus = guest.rsvpStatus,
                ticketCode = ticket?.ticketCode,
                ticketStatus = ticket?.status,
                checkedInAt = ticket?.checkedInAt,
                checkedInBy = ticket?.checkedInBy,
            )
        }
    }

    /**
     * Applies a batch of check-ins captured offline, scoped to the event and
     * attributed to the validator. Conflicts between devices are resolved
     * first-timestamp-wins by the ticketing module; each item's result lets the
     * device reconcile its local state.
     */
    fun sync(
        eventId: UUID,
        validatorLabel: String,
        items: List<SyncItem>,
    ): List<SyncResultEntry> {
        if (eventCancelled(eventId)) {
            return items.map { SyncResultEntry(it.ticketCode, EVENT_CANCELLED, null, null, null) }
        }
        return items.map { item ->
            val ticket = ticketing.findByCode(item.ticketCode)
            if (ticket == null || ticket.eventId != eventId) {
                SyncResultEntry(item.ticketCode, CheckInOutcome.NOT_FOUND.name, null, null, null)
            } else {
                val result = ticketing.syncCheckInByCode(item.ticketCode, validatorLabel, item.scannedAt)
                val guestName = result.ticket?.let { invitation.findGuest(it.guestId)?.fullName() }
                SyncResultEntry(
                    ticketCode = item.ticketCode,
                    outcome = result.outcome.name,
                    guestName = guestName,
                    checkedInAt = result.checkedInAt,
                    checkedInBy = result.checkedInBy,
                )
            }
        }
    }

    private fun respond(result: CheckInResult): CheckInResponse {
        val guestName = result.ticket?.let { invitation.findGuest(it.guestId)?.fullName() }
        return CheckInResponse(
            outcome = result.outcome.name,
            guestName = guestName,
            ticketCode = result.ticket?.ticketCode,
            checkedInAt = result.checkedInAt,
            checkedInBy = result.checkedInBy,
        )
    }

    private fun notFound() = CheckInResponse(CheckInOutcome.NOT_FOUND.name, null, null, null, null)

    /**
     * A cancelled event's tickets must never validate — and the validator must see
     * why explicitly, not a generic failure (JIKU-14B).
     */
    private fun eventCancelled(eventId: UUID): Boolean = events.findEvent(eventId)?.status == EventInfo.STATUS_CANCELLED

    private fun eventCancelledResponse() = CheckInResponse(EVENT_CANCELLED, null, null, null, null)

    private fun GuestInfo.fullName(): String = "$firstName $lastName"

    companion object {
        /**
         * Check-in-level outcome (alongside the ticketing module's
         * [CheckInOutcome] names): the event itself was cancelled, so no ticket
         * of it can validate regardless of the ticket's own state.
         */
        const val EVENT_CANCELLED = "EVENT_CANCELLED"
    }
}
