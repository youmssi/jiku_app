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
     * Provisions a brand-new tenant and owner account for a customer who has not
     * registered yet (JIKU-55: booking deposit verification). Reuses the
     * existing account if [ownerEmail] already has one — a repeat customer gets
     * a second organization rather than a duplicate identity. Creates the OWNER
     * membership and emails an account-access link through the same
     * password-reset flow "forgot password" uses, so no new email template is
     * needed for a first login. Returns the new tenant's id.
     */
    fun provisionTenant(
        organizationName: String,
        ownerEmail: String,
        ownerFullName: String?,
    ): UUID
}
