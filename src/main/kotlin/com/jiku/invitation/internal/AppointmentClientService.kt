package com.jiku.invitation.internal

import com.jiku.shared.AppointmentBooked
import com.jiku.shared.WalkInArrived
import com.jiku.ticket.TicketingModuleApi
import org.springframework.stereotype.Service as SpringService

/**
 * Matérialise les clients sans compte d'un service (JIKU-87, JIKU-88) : crée
 * l'invité (sans événement) puis émet son billet. Exécuté dans la transaction du
 * module catalog, via les événements [AppointmentBooked] et [WalkInArrived].
 */
@SpringService
class AppointmentClientService(
    private val guests: GuestRepository,
    private val ticketing: TicketingModuleApi,
) {
    fun record(booking: AppointmentBooked) {
        val guest = saveGuest(booking.clientName, booking.clientPhone)
        ticketing.issueAppointment(
            guestId = requireNotNull(guest.id),
            startsAt = booking.startsAt,
            endsAt = booking.endsAt,
            serviceId = booking.serviceId,
            professionalName = booking.professionalName,
            clientName = booking.clientName,
            clientPhone = booking.clientPhone,
        )
    }

    fun recordWalkIn(walkIn: WalkInArrived) {
        val guest = saveGuest(walkIn.clientName, walkIn.clientPhone)
        ticketing.issueWalkIn(
            guestId = requireNotNull(guest.id),
            serviceId = walkIn.serviceId,
            clientName = walkIn.clientName,
            clientPhone = walkIn.clientPhone,
            professionalName = walkIn.professionalName,
            arrivedAt = walkIn.arrivedAt,
            dayStart = walkIn.dayStart,
            dayEnd = walkIn.dayEnd,
            rankDay = walkIn.rankDay,
        )
    }

    private fun saveGuest(
        firstName: String,
        phoneNumber: String,
    ) = guests.save(Guest(eventId = null, firstName = firstName, lastName = "", phoneNumber = phoneNumber))
}
