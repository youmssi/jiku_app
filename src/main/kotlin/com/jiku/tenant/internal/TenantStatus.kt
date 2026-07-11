package com.jiku.tenant.internal

/**
 * Lifecycle status of a tenant. [SUSPENDED] is a platform-level kill switch
 * (JIKU-40): it blocks organizer login, rejects existing tokens, and stops
 * guest/validator links from resolving.
 */
enum class TenantStatus {
    ACTIVE,
    SUSPENDED,
}
