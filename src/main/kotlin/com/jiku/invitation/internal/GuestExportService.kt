package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
import com.jiku.ticket.TicketingModuleApi
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Streams an event's guest list to CSV: identity, contact, per-channel invitation
 * status, RSVP status, and check-in status/time. Rows are written straight to the
 * response via [CSVPrinter] rather than assembled into one large in-memory string,
 * keeping export viable up to the import cap. Tenant isolation is enforced by the
 * persistence filter on every read.
 */
@Service
class GuestExportService(
    private val guests: GuestRepository,
    private val invitations: InvitationRepository,
    private val events: EventModuleApi,
    private val ticketing: TicketingModuleApi,
) {
    @Transactional(readOnly = true)
    fun writeCsv(
        eventId: UUID,
        appendable: Appendable,
    ) {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

        val allGuests = guests.findByEventId(eventId)
        // En-tête de quorum (JIKU-94) : c'est ce qui fait de l'export une preuve
        // opposable plutôt qu'une simple liste. Absent si aucun quorum n'est
        // configuré, pour ne pas polluer l'export d'un mariage.
        val quorum =
            events.quorum(
                eventId,
                totalGuests = allGuests.size.toLong(),
                checkedIn = ticketing.attendanceStats(eventId).checkedIn,
            )
        if (quorum != null) {
            val atteint = quorum.reachedAt?.toString() ?: "non atteint"
            appendable.append("Quorum requis,").append(quorum.required.toString()).append(NEWLINE)
            appendable.append("Presents,").append(quorum.current.toString()).append(NEWLINE)
            appendable.append("Atteint le,").append(atteint).append(NEWLINE)
            appendable.append(NEWLINE)
        }

        val invitationByGuestChannel =
            invitations.findByEventId(eventId).associateBy { it.guestId to it.channel }
        val ticketByGuest = ticketing.findTicketsByEvent(eventId).associateBy { it.guestId }

        val format =
            CSVFormat.DEFAULT
                .builder()
                .setHeader(*HEADERS)
                .get()

        CSVPrinter(appendable, format).use { printer ->
            for (guest in allGuests) {
                val guestId = requireNotNull(guest.id)
                val ticket = ticketByGuest[guestId]
                printer.printRecord(
                    guest.firstName,
                    guest.lastName,
                    guest.email ?: "",
                    guest.phoneNumber ?: "",
                    invitationByGuestChannel[guestId to InvitationChannel.EMAIL]?.status?.name ?: "",
                    invitationByGuestChannel[guestId to InvitationChannel.WHATSAPP]?.status?.name ?: "",
                    guest.rsvpStatus.name,
                    ticket?.status ?: "",
                    ticket?.checkedInAt?.toString() ?: "",
                    ticket?.checkedInBy ?: "",
                )
            }
        }
    }

    private companion object {
        /** Séparateur explicite : le CSV doit être identique quelle que soit la plateforme. */
        const val NEWLINE = "\n"

        val HEADERS =
            arrayOf(
                "First name",
                "Last name",
                "Email",
                "Phone",
                "Email invitation",
                "WhatsApp invitation",
                "RSVP",
                "Check-in",
                "Checked in at (UTC)",
                "Checked in by",
            )
    }
}
