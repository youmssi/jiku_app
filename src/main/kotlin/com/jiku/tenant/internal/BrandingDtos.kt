package com.jiku.tenant.internal

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * Branding update. Every field is optional; sending a field with a blank value
 * clears it back to the default, so a client sends back the links it read. The
 * logo and banner are uploaded through `/branding/logo` and `/branding/banner`
 * (JIKU-259), which set these links; a link to a photo hosted elsewhere is
 * still accepted.
 */
data class UpdateBrandingRequest(
    @field:Size(max = 255)
    val displayName: String? = null,
    @field:Size(max = 2048)
    val logoUrl: String? = null,
    @field:Size(max = 2048)
    val bannerUrl: String? = null,
    @field:Pattern(
        regexp = "^#[0-9a-fA-F]{6}$",
        message = "primaryColor must be a 6-digit hex color, e.g. #1E293B",
    )
    val primaryColor: String? = null,
)

data class BrandingResponse(
    val displayName: String,
    val logoUrl: String?,
    val bannerUrl: String?,
    val primaryColor: String,
)
