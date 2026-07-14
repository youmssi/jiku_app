package com.jiku.shared

/**
 * Whether a user currently belongs to an organization (JIKU-50). Exposed in
 * `shared` for the same reason as [TenantAccessGate]: the security filter must
 * consult membership on every tenant-bound request — so removing a member takes
 * effect on their next request, not at token expiry — without depending on the
 * tenant module. The tenant module provides the implementation.
 */
interface MembershipAccessGate {
    fun isMember(
        userId: String,
        tenantId: String,
    ): Boolean
}
