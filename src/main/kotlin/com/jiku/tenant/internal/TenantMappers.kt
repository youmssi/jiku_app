package com.jiku.tenant.internal

import com.jiku.tenant.TenantInfo

/** Default brand color used when a tenant has not chosen one (Jikū neutral). */
const val DEFAULT_PRIMARY_COLOR = "#1E293B"

private fun Tenant.effectiveDisplayName(): String = branding?.displayName?.takeIf { it.isNotBlank() } ?: name

private fun Tenant.effectivePrimaryColor(): String = branding?.primaryColor?.takeIf { it.isNotBlank() } ?: DEFAULT_PRIMARY_COLOR

fun Tenant.toTenantInfo(): TenantInfo =
    TenantInfo(
        id = requireNotNull(id),
        name = name,
        contactEmail = contactEmail,
        status = status.name,
        createdAt = createdAt,
        displayName = effectiveDisplayName(),
        logoUrl = branding?.logoUrl,
        primaryColor = effectivePrimaryColor(),
    )

fun Tenant.toBrandingResponse(): BrandingResponse =
    BrandingResponse(
        displayName = effectiveDisplayName(),
        logoUrl = branding?.logoUrl,
        primaryColor = effectivePrimaryColor(),
    )
