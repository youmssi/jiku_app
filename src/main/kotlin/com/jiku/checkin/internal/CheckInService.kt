package com.jiku.checkin.internal

import com.jiku.invitation.GuestInfo
import com.jiku.invitation.InvitationModuleApi
import com.jiku.ticketing.CheckInOutcome
import com.jiku.ticketing.CheckInResult
import com.jiku.ticketing.TicketingModuleApi
import org.springframework.stereotype.Service
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
) {
    fun checkInByCode(
        eventId: UUID,
        ticketCode: String,
        validatorLabel: String,
    ): CheckInResponse {
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
        val guest = invitation.findGuest(guestId)
        if (guest == null || guest.eventId != eventId) {
            return notFound()
        }
        return respond(ticketing.checkInByGuest(guestId, validatorLabel))
    }

    fun search(
        eventId: UUID,
        query: String,
    ): List<GuestMatch> =
        invitation.searchGuests(eventId, query).map { guest ->
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

    fun stats(eventId: UUID): AttendanceResponse {
        val stats = ticketing.attendanceStats(eventId)
        return AttendanceResponse(checkedIn = stats.checkedIn, confirmed = stats.confirmed)
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

    private fun GuestInfo.fullName(): String = "$firstName $lastName"
}
