package com.jiku.invitation.internal

import com.jiku.event.EventInfo
import com.jiku.event.EventModuleApi
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import com.jiku.ticketing.TicketingModuleApi
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
            if (!events.reserveAttendanceSlot(eventId)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "This event is full")
            }
            guest.rsvpStatus = RsvpStatus.CONFIRMED
            guests.save(guest)
            ticketing.issueTicket(eventId, guestId)
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
            events.releaseAttendanceSlot(eventId)
            ticketing.cancelByGuest(guestId)
        }
        guest.rsvpStatus = RsvpStatus.DECLINED
        guests.save(guest)
        return buildView(guest)
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
        )
    }

    private fun formatWhen(
        instant: Instant,
        timezone: String,
    ): String = WHEN_FORMAT.withZone(ZoneId.of(timezone)).format(instant)

    private companion object {
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm")
    }
}
