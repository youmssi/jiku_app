package com.jiku.shared

/**
 * Platform-level tenant kill switch (JIKU-40), exposed in `shared` for the same
 * reason as [UsageAllowanceGate]: the security filter and the public token-based
 * flows (guest RSVP, validator check-in) must consult tenant status without
 * depending on the tenant module. The tenant module provides the implementation.
 */
interface TenantAccessGate {
    /** Whether the tenant is suspended and must be denied all access. */
    fun isSuspended(tenantId: String): Boolean
}
