package com.jiku.tenant

import java.util.UUID

/**
 * The tenant module's public API. Other modules read tenant information through
 * this interface only — never through the tenant repository or entity, which are
 * internal to the module.
 */
interface TenantModuleApi {
    fun findTenant(tenantId: UUID): TenantInfo?

    /**
     * Pages through tenants for the platform-admin directory (JIKU-40), optionally
     * filtered by a case-insensitive match on name or contact email.
     */
    fun searchTenants(
        query: String?,
        page: Int,
        size: Int,
    ): TenantDirectoryPage

    /**
     * Suspends or reactivates a tenant (JIKU-40). Returns the updated view, or
     * `null` when the tenant does not exist. Suspension is enforced by the
     * [com.jiku.shared.TenantAccessGate] implementation this module provides.
     */
    fun setTenantSuspended(
        tenantId: UUID,
        suspended: Boolean,
    ): TenantInfo?

    /**
     * The tenant owning a public username (case-insensitive), or null. Backs the
     * discoverable organization profile; suspension is the caller's concern.
     */
    fun findByUsername(username: String): TenantInfo?

    /** Binds (or clears, with null) the calling tenant's public username. */
    fun updateUsername(
        tenantId: UUID,
        username: String?,
    ): TenantInfo
}
