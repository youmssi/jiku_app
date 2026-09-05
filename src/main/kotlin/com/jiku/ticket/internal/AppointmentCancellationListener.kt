package com.jiku.ticket.internal

import com.jiku.shared.AppointmentCancelled
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Annule les billets d'un rendez-vous que le client vient de supprimer (JIKU-89).
 * Publié par le module catalog dans la transaction d'annulation (les lignes
 * service_reservation sont effacées) ; ce listener s'y joint pour mettre les
 * billets du créneau à CANCELLED dans la même transaction — un rendez-vous
 * annulé ne doit ni apparaître sur la ligne du jour ni déclencher un rappel.
 *
 * Les billets sont chargés par le dépôt filtré par tenant puis mis à jour un à
 * un, jamais par JPQL en masse, qui contournerait le filtre de tenant (voir
 * EventCancellationListener).
 */
@Component
class AppointmentCancellationListener(
    private val tickets: TicketRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onAppointmentCancelled(event: AppointmentCancelled) {
        tickets
            .findByServiceIdAndStartsAtAndStatusAndKind(
                event.serviceId,
                event.startsAt,
                TicketStatus.ISSUED,
                TicketKind.APPOINTMENT,
            ).forEach {
                it.status = TicketStatus.CANCELLED
            }
    }
}
