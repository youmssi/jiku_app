package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class ServiceCreateRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotBlank val timezone: String,
)

data class ServiceUpdateRequest(
    @field:Size(max = 120) val name: String? = null,
)

data class ServiceResponse(
    val id: UUID,
    val name: String,
    val timezone: String,
)

data class ServiceRequirementRequest(
    val type: ResourceType,
    @field:Min(1) @field:Max(100) val quantity: Int,
)

data class ServiceRequirementResponse(
    val id: UUID,
    val serviceId: UUID,
    val type: ResourceType,
    val quantity: Int,
)

data class BookingLinkResponse(
    val token: String,
)
