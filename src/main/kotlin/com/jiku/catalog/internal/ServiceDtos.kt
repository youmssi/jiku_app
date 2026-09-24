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
    val paymentRule: PaymentRule = PaymentRule.FREE,
    /** Required unless the service is free; in the organization's currency. */
    val priceMinor: Long? = null,
)

/** Absent fields stay unchanged; the payment rule and price change together. */
data class ServiceUpdateRequest(
    @field:Size(max = 120) val name: String? = null,
    val paymentRule: PaymentRule? = null,
    val priceMinor: Long? = null,
)

data class ServiceResponse(
    val id: UUID,
    val name: String,
    val timezone: String,
    val paymentRule: PaymentRule,
    /** Null for a free service. */
    val priceMinor: Long?,
    val currency: String?,
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
    /** Code court stable partagé sur https://…/r/<code> ; le jeton reste pour la rétrocompatibilité. */
    val shortCode: String,
)
