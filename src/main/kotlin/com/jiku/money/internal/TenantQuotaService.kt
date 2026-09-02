package com.jiku.money.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The cumulative, per-tenant free-tier budget (JIKU-54). This is the single
 * place that reads or writes a [TenantQuota] — both the paywall gate
 * ([UsageAllowanceGateAdapter]) and the read-facing usage snapshot
 * ([UsageService]) consult [freeCeilingFor] so the two never disagree about how
 * much free allowance an event actually has left.
 */
@Service
class TenantQuotaService(
    private val quotas: TenantQuotaRepository,
    private val properties: BillingProperties,
) {
    /** The current tenant's quota, rolling the window forward first if it has elapsed. */
    @Transactional
    fun currentQuota(): TenantQuota {
        val now = Instant.now()
        val existing = quotas.findFirstByOrderByIdAsc()
        if (existing == null) {
            return quotas.save(TenantQuota(freeInvitesLimit = properties.freeTierGuests).also { it.windowStart = now })
        }
        if (now.isAfter(existing.windowStart.plus(properties.freeTierWindow))) {
            existing.freeInvitesUsed = 0
            existing.windowStart = now
            existing.updatedAt = now
            return quotas.save(existing)
        }
        return existing
    }

    /**
     * The free-tier ceiling for an event that has already locked in
     * [alreadyCommitted] distinct guests of its own: the maximum total it may
     * reach, comparable directly against its own committed count — the same
     * shape the free tier always had. [alreadyCommitted] is added back before
     * subtracting the tenant-wide total so it is never subtracted twice (once
     * here, once by the caller comparing its own committed count against the
     * returned ceiling).
     */
    @Transactional(readOnly = true)
    fun freeCeilingFor(alreadyCommitted: Long): Long {
        val quota = currentQuota()
        val usedByOtherEvents = (quota.freeInvitesUsed - alreadyCommitted).coerceAtLeast(0)
        return (quota.freeInvitesLimit - usedByOtherEvents).coerceAtLeast(0)
    }

    /**
     * Locks in [newGuestCount] newly committed free-tier guests against the
     * tenant's cumulative budget. Called only after [freeCeilingFor] has
     * already confirmed room for this send — never blocks.
     */
    @Transactional
    fun recordFreeCommitment(newGuestCount: Long) {
        if (newGuestCount <= 0) return
        val quota = currentQuota()
        quota.freeInvitesUsed = (quota.freeInvitesUsed + newGuestCount).coerceAtMost(quota.freeInvitesLimit)
        quota.updatedAt = Instant.now()
        quotas.save(quota)
    }
}
