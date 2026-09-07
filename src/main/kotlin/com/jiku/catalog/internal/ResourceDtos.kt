package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalTime

data class ResourceCreateRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    val type: ResourceType,
    @field:NotBlank val timezone: String,
)

data class ResourceUpdateRequest(
    @field:Size(max = 120) val name: String? = null,
    val active: Boolean? = null,
)

data class AvailabilityRequest(
    @field:Min(1) @field:Max(7) val dayOfWeek: Int,
    val start: LocalTime,
    val end: LocalTime,
)

data class UnavailabilityRequest(
    val startsAt: Instant,
    val endsAt: Instant,
    val reason: String? = null,
)

data class FreeSlotResponse(
    val free: Boolean,
)
