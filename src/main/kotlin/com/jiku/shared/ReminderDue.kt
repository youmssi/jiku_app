package com.jiku.shared

import java.time.Instant
import java.util.UUID

/**
 * Un rappel de rendez-vous doit partir (JIKU-89). Publié par le module ticket
 * après avoir réservé la ligne d'idempotence (ticket + décalage) ; le module
 * messaging écoute et l'expédie par le chemin d'envoi habituel, garde-fous
 * WhatsApp compris, puis publie [ReminderDelivered] pour clôturer la ligne.
 */
data class ReminderDue(
    val reminderId: UUID,
    val serviceId: UUID,
    val tenantId: String,
    val offsetMinutes: Int,
    val clientName: String?,
    val clientPhone: String,
    val startsAt: Instant,
    val professionalName: String?,
    /** Fuseau du service, pour écrire l'heure du rendez-vous côté client. */
    val serviceTimezone: String,
)
