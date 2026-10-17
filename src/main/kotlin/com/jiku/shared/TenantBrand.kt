package com.jiku.shared

/**
 * The name and logo a client sees for an organization on its public pages
 * (JIKU-249), exposed in `shared` for the same reason as [TenantCurrency]: the
 * catalog shows them on a booking page but cannot depend on the tenant module,
 * which depends on it. The tenant module provides the implementation.
 */
fun interface TenantBrand {
    /** The organization's display name and logo, or null when [tenantId] does not exist. */
    fun of(tenantId: String): TenantBrandInfo?
}

data class TenantBrandInfo(
    val name: String,
    val logoUrl: String?,
)
