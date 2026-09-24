package com.jiku.tenant.internal

import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantLegalIdentityInfo

/** Default brand color used when a tenant has not chosen one (Jikū neutral). */
const val DEFAULT_PRIMARY_COLOR = "#1E293B"

internal fun Tenant.effectiveDisplayName(): String = branding?.displayName?.takeIf { it.isNotBlank() } ?: name

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
        bannerUrl = branding?.bannerUrl,
        primaryColor = effectivePrimaryColor(),
        legalIdentity = legalIdentity?.toInfo(),
        username = username,
    )

/**
 * Only a complete identity crosses the boundary. A half-filled one is not a
 * usable buyer block, and letting it through would push the completeness check
 * into every consumer instead of settling it here.
 */
private fun TenantLegalIdentity.toInfo(): TenantLegalIdentityInfo? =
    if (!isComplete()) {
        null
    } else {
        TenantLegalIdentityInfo(
            legalName = requireNotNull(legalName),
            registrationNumber = registrationNumber,
            taxIdentifier = taxIdentifier,
            addressLine = requireNotNull(addressLine),
            city = requireNotNull(city),
            country = requireNotNull(country),
        )
    }

fun Tenant.toBrandingResponse(): BrandingResponse =
    BrandingResponse(
        displayName = effectiveDisplayName(),
        logoUrl = branding?.logoUrl,
        bannerUrl = branding?.bannerUrl,
        primaryColor = effectivePrimaryColor(),
    )
