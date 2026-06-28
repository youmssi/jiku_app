package com.jiku.tenant.internal

/**
 * Lifecycle status of a tenant. Only [ACTIVE] is used at MVP stage; [SUSPENDED]
 * exists for the future billing/abuse flows.
 */
enum class TenantStatus {
    ACTIVE,
    SUSPENDED,
}
