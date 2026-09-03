package com.jiku.catalog.internal

import com.jiku.catalog.InvitationChannel
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class EventSettingsDto(
    val placementEnabled: Boolean = false,
    val transferAllowed: Boolean = false,
    val transferDeadline: Instant? = null,
    val overbookingAllowed: Boolean = false,
    val maxOverbookingCount: Int? = null,
)

data class CreateEventRequest(
    @field:NotBlank val name: String,
    val description: String? = null,
    val startDateTime: Instant? = null,
    val endDateTime: Instant? = null,
    @field:NotBlank val timezone: String,
    val location: String? = null,
    val maxCapacity: Int? = null,
    val settings: EventSettingsDto = EventSettingsDto(),
    val invitationChannels: Set<InvitationChannel> = emptySet(),
)

data class UpdateEventRequest(
    @field:NotBlank val name: String,
    val description: String? = null,
    val startDateTime: Instant? = null,
    val endDateTime: Instant? = null,
    @field:NotBlank val timezone: String,
    val location: String? = null,
    val maxCapacity: Int? = null,
    val settings: EventSettingsDto = EventSettingsDto(),
    val invitationChannels: Set<InvitationChannel> = emptySet(),
)

data class EventResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val startDateTime: Instant?,
    val endDateTime: Instant?,
    val timezone: String,
    val location: String?,
    val maxCapacity: Int?,
    val status: String,
    val settings: EventSettingsDto,
    val invitationChannels: Set<InvitationChannel>,
    /** Règle de quorum, si l'organisateur en a défini une (JIKU-94). */
    val quorum: QuorumResponse? = null,
)

/**
 * Règle de quorum saisie par l'organisateur (JIKU-94). C'est une règle
 * statutaire propre à chaque organisation : elle est saisie, jamais devinée.
 *
 * `mode = NONE` efface la règle et rend à l'événement son comportement
 * d'origine — aucune carte quorum, aucun horodatage.
 */
data class UpdateQuorumRequest(
    @field:NotNull
    val mode: QuorumMode,
    @field:Positive
    val numerator: Int? = null,
    @field:Positive
    val denominator: Int? = null,
    @field:Positive
    val absolute: Int? = null,
)

data class QuorumResponse(
    val mode: String,
    val numerator: Int?,
    val denominator: Int?,
    val absolute: Int?,
    val reachedAt: Instant?,
)

/**
 * Catégorie d'accès d'un événement (JIKU-93) : salle, carré VIP, accès scène.
 *
 * `maxCapacity` nul signifie « pas de plafond propre » : seule la capacité de
 * l'événement s'applique alors.
 */
data class UpsertTicketTypeRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val label: String,
    @field:Positive
    val maxCapacity: Int? = null,
    @field:Pattern(
        regexp = "^#[0-9a-fA-F]{6}$",
        message = "colorHex doit être une couleur hexadécimale à 6 chiffres, par exemple #1E293B",
    )
    val colorHex: String = "#1E293B",
    val position: Int = 0,
)

data class TicketTypeResponse(
    val id: UUID,
    val label: String,
    val colorHex: String,
    val maxCapacity: Int?,
    val confirmedCount: Int,
    val position: Int,
)
