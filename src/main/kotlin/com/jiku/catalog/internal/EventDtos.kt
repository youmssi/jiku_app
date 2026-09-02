package com.jiku.catalog.internal

import com.jiku.catalog.InvitationChannel
import jakarta.validation.constraints.NotBlank
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
)
