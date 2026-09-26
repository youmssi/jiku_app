package com.jiku.catalog.internal

import com.jiku.ticket.LineTicket
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Vue de la ligne du jour d'un service pour l'écran du comptoir (JIKU-88) : le
 * service, sa date (dans son fuseau) et la liste des entrées mêlant rendez-vous
 * et sans-rendez-vous.
 */
data class DayLineView(
    val serviceId: UUID,
    val serviceName: String,
    val timezone: String,
    val date: LocalDate,
    val entries: List<LineTicket>,
)

/** Sans-rendez-vous inscrit au comptoir (JIKU-88). */
data class WalkInRequest(
    @field:NotBlank @field:Size(max = 120) val clientName: String,
    @field:NotBlank @field:Size(max = 32) val clientPhone: String,
)

/** Réponse de l'action « suivant » : la personne appelée, ou null si personne n'attend. */
data class NextResponse(
    val ticket: LineTicket?,
)

/**
 * Demande de rendez-vous en attente de confirmation (JIKU-87/88), mode « sur
 * demande ». Une demande bloque les créneaux de ses ressources jusqu'à [heldUntil]
 * tant qu'aucune décision n'est prise ; l'organisateur ou le comptoir la confirme
 * ou la refuse depuis la console.
 */
data class PendingAppointmentRequest(
    val id: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val clientName: String?,
    val clientPhone: String?,
    val requestedAt: Instant,
    val heldUntil: Instant?,
)

/** Création d'un lien du personnel pour un service (JIKU-88). */
data class ServiceStaffCreateRequest(
    @field:NotBlank @field:Size(max = 80) val label: String,
)

/**
 * Lien du personnel venant d'être créé. [code] est le lien court partageable
 * (`/line/{code}`, JIKU-88) — résolu à chaque usage en un jeton signé frais,
 * il peut être recopié à tout moment ; [token] n'est montré qu'ici.
 */
data class ServiceStaffCreatedResponse(
    val id: UUID,
    val label: String,
    val token: String,
    val code: String?,
    val createdAt: Instant,
)

/** Ligne du personnel d'un service, sans le jeton (consultation, révocation). */
data class ServiceStaffView(
    val id: UUID,
    val serviceId: UUID,
    val label: String,
    val revoked: Boolean,
    val code: String?,
    val createdAt: Instant,
    val revokedAt: Instant?,
)
