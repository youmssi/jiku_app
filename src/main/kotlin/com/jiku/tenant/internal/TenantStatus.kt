package com.jiku.tenant.internal

/**
 * Lifecycle status of a tenant. [SUSPENDED] is a platform-level kill switch
 * (JIKU-40): it blocks organizer login, rejects existing tokens, and stops
 * guest/validator links from resolving. [CLOSING] behaves the same while its
 * owner's account deletion waits out its grace period (JIKU-316), and
 * [CLOSED] is final: its personal data has been erased.
 */
enum class TenantStatus {
    ACTIVE,
    SUSPENDED,
    CLOSING,
    CLOSED,
}
