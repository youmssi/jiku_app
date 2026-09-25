package com.jiku.checkin.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.OperatorAction
import com.jiku.invitation.GuestInfo
import com.jiku.invitation.InvitationModuleApi
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import com.jiku.ticket.CheckInOutcome
import com.jiku.ticket.CheckInResult
import com.jiku.ticket.TicketInfo
import com.jiku.ticket.TicketPaymentMethod
import com.jiku.ticket.TicketPaymentOutcome
import com.jiku.ticket.TicketingModuleApi
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
    private val log = org.slf4j.LoggerFactory.getLogger(CheckInService::class.java)

    /**
     * Branding and live attendance context for the operator [operatorLabel] opening
     * [eventId]'s door, allowed [actions] there. The tenant is already bound by the caller.
     */
    fun context(
        eventId: UUID,
        operatorLabel: String,
        actions: Set<OperatorAction>,
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
            organizerName = event?.brand?.name ?: tenant?.displayName ?: "Your organizer",
            primaryColor = event?.brand?.primaryColor ?: tenant?.primaryColor ?: "#1E293B",
            logoUrl = event?.brand?.logoUrl ?: tenant?.logoUrl,
            validatorLabel = operatorLabel,
            actions = actions,
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
        return respond(eventId, ticketing.checkInByCode(ticketCode, validatorLabel))
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
        return respond(eventId, ticketing.checkInByGuest(guestId, validatorLabel))
    }

    fun search(
        eventId: UUID,
        query: String,
    ): List<GuestMatch> {
        if (eventCancelled(eventId)) {
            throw ResponseStatusException(HttpStatus.GONE, "This event has been cancelled")
        }
        val typesById = events.ticketTypes(eventId).associateBy { it.id }
        val ticketsByGuest = ticketing.findTicketsByEvent(eventId).associateBy { it.guestId }
        return invitation.searchGuests(eventId, query).map { guest ->
            val ticket = ticketsByGuest[guest.id]
            val type = ticket?.ticketTypeId?.let { typesById[it] }
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
                ticketTypeLabel = type?.label,
                ticketTypeColor = type?.colorHex,
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
        val typesById = events.ticketTypes(eventId).associateBy { it.id }
        return invitation.listGuests(eventId).map { guest ->
            val ticket = ticketsByGuest[guest.id]
            val type = ticket?.ticketTypeId?.let { typesById[it] }
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
                ticketTypeLabel = type?.label,
                ticketTypeColor = type?.colorHex,
                paymentStatus = ticket?.paymentStatus,
            )
        }
    }

    /**
     * Records that a guest paid the organization for their ticket (JIKU-110),
     * attributed to [operatorLabel]. Only a ticket of this event can be marked.
     */
    fun markPaid(
        eventId: UUID,
        ticketCode: String,
        method: TicketPaymentMethod,
        operatorLabel: String,
    ): TicketInfo {
        if (eventCancelled(eventId)) {
            throw ResponseStatusException(HttpStatus.GONE, "This event has been cancelled")
        }
        ticketing.findByCode(ticketCode)?.takeIf { it.eventId == eventId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No ticket with this code for this event")
        val result = ticketing.markPaidByCode(ticketCode, method, operatorLabel)
        if (result.outcome != TicketPaymentOutcome.PAID) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Nothing is owed on this ticket, or it is already paid")
        }
        return requireNotNull(result.ticket)
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

    private fun respond(
        eventId: UUID,
        result: CheckInResult,
    ): CheckInResponse {
        if (result.outcome == CheckInOutcome.CHECKED_IN) {
            recordQuorumIfReached(eventId)
        }
        val guestName = result.ticket?.let { invitation.findGuest(it.guestId)?.fullName() }
        // La catégorie vient du billet, pas de l'invité : si l'organisateur a
        // reclassé quelqu'un après émission, le portier doit voir ce que porte
        // le billet présenté.
        val type =
            result.ticket?.ticketTypeId?.let { typeId ->
                events.ticketTypes(eventId).firstOrNull { it.id == typeId }
            }
        return CheckInResponse(
            outcome = result.outcome.name,
            guestName = guestName,
            ticketCode = result.ticket?.ticketCode,
            checkedInAt = result.checkedInAt,
            checkedInBy = result.checkedInBy,
            ticketTypeLabel = type?.label,
            ticketTypeColor = type?.colorHex,
            amountDueMinor = result.ticket?.takeIf { result.outcome == CheckInOutcome.PAYMENT_DUE }?.amountDueMinor,
            amountDueCurrency = result.ticket?.takeIf { result.outcome == CheckInOutcome.PAYMENT_DUE }?.amountDueCurrency,
        )
    }

    /**
     * Horodate l'atteinte du quorum si cette entrée vient de la franchir
     * (JIKU-94). L'écriture est conditionnelle en base — `WHERE reached_at IS
     * NULL` — donc deux portiers qui scannent simultanément au franchissement
     * n'enregistrent qu'une seule date, et les arrivées suivantes ne la
     * réécrivent jamais.
     *
     * Un échec ici ne doit jamais faire échouer une entrée : le portier a scanné,
     * la personne est admise. Le quorum est une lecture de cet état, pas une
     * condition de son enregistrement.
     */
    private fun recordQuorumIfReached(eventId: UUID) {
        try {
            val guests = invitation.guestStats(eventId)
            val stats = ticketing.attendanceStats(eventId)
            val quorum = events.quorum(eventId, guests.total, stats.checkedIn) ?: return
            if (quorum.reached && quorum.reachedAt == null) {
                events.markQuorumReached(eventId)
            }
        } catch (ex: Exception) {
            log.warn("Impossible d'horodater le quorum pour l'événement {}", eventId, ex)
        }
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
