package com.jiku.invitation.internal

import com.jiku.shared.AppointmentBooked
import com.jiku.ticket.TicketingModuleApi
import org.springframework.stereotype.Service as SpringService

/**
 * Matérialise un rendez-vous sans compte (JIKU-87) : crée l'invité (sans
 * événement) puis émet son billet de rendez-vous portant le créneau. Exécuté dans
 * la transaction de la réservation, via l'événement [AppointmentBooked].
 */
@SpringService
class AppointmentClientService(
    private val guests: GuestRepository,
    private val ticketing: TicketingModuleApi,
) {
    fun record(booking: AppointmentBooked) {
        val guest =
            guests.save(
                Guest(
                    eventId = null,
                    firstName = booking.clientName,
                    lastName = "",
                    phoneNumber = booking.clientPhone,
                ),
            )
        ticketing.issueAppointment(
            guestId = requireNotNull(guest.id),
            startsAt = booking.startsAt,
            endsAt = booking.endsAt,
            serviceId = booking.serviceId,
            professionalName = booking.professionalName,
        )
    }
}
