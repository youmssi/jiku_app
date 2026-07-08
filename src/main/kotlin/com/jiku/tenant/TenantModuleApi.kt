package com.jiku.tenant

import java.util.UUID

/**
 * The tenant module's public API. Other modules read tenant information through
 * this interface only — never through the tenant repository or entity, which are
 * internal to the module.
 */
interface TenantModuleApi {
    fun findTenant(tenantId: UUID): TenantInfo?
}
