package com.jiku.shared

import java.time.Instant
import java.util.UUID

/**
 * Un client a annulé son rendez-vous (JIKU-87/89). Publié par le module catalog
 * quand les lignes de service_reservation d'un jeton sont supprimées ; le module
 * ticket écoute pour annuler le billet de rendez-vous correspondant, afin qu'un
 * rendez-vous annulé ne déclenche ni rappel ni apparition sur la ligne du jour.
 */
data class AppointmentCancelled(
    val serviceId: UUID,
    val startsAt: Instant,
    val tenantId: String,
)
