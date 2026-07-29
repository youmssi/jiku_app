package com.jiku.billing.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TenantQuotaRepository : JpaRepository<TenantQuota, UUID> {
    /** The current tenant's quota row, if one exists yet — there is at most one per tenant. */
    fun findFirstByOrderByIdAsc(): TenantQuota?
}
