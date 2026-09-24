package com.jiku.shared

/**
 * The currency an organization prices in (JIKU-107), exposed in `shared` for the
 * same reason as [TenantAccessGate]: the catalog prices tickets and services but
 * cannot depend on the tenant module, which depends on it. The tenant module
 * provides the implementation.
 */
fun interface TenantCurrency {
    /** ISO 4217 code of the tenant's currency. */
    fun of(tenantId: String): String
}
