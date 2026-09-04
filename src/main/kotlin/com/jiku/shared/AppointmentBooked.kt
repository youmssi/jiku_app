package com.jiku.shared

import java.time.Instant
import java.util.UUID

/**
 * Un client sans compte vient de réserver un créneau (JIKU-87). Publié par le
 * module catalog après une réservation réussie ; le module invitation écoute pour
 * matérialiser l'invité et son billet de rendez-vous.
 */
data class AppointmentBooked(
    val serviceId: UUID,
    val tenantId: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val clientName: String,
    val clientPhone: String,
    /** Nom du professionnel à inscrire sur le billet (JIKU-87). */
    val professionalName: String?,
)
