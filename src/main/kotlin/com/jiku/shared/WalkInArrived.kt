package com.jiku.shared

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Un client sans-rendez-vous vient d'être inscrit au comptoir (JIKU-88). Publié
 * par le module catalog (la console de ligne du jour) ; le module invitation
 * écoute pour matérialiser l'invité et son billet de sans-rendez-vous, déjà en
 * attente avec son rang du jour ([rankDay], journée locale du service sur
 * laquelle le rang est alloué).
 */
data class WalkInArrived(
    val serviceId: UUID,
    val tenantId: String,
    val clientName: String,
    val clientPhone: String,
    val professionalName: String?,
    val arrivedAt: Instant,
    val dayStart: Instant,
    val dayEnd: Instant,
    val rankDay: LocalDate,
)
