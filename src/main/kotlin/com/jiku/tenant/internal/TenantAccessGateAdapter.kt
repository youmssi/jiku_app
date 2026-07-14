package com.jiku.tenant.internal

import com.jiku.shared.TenantAccessGate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tenant-module implementation of the platform kill switch (JIKU-40). The check
 * runs on every authenticated request and on every guest/validator link
 * resolution, so lookups are cached briefly; a status change invalidates its
 * entry immediately (see TenantModuleApiService), making the TTL only a bound on
 * staleness across multiple application instances.
 */
@Component
class TenantAccessGateAdapter(
    private val tenants: TenantRepository,
) : TenantAccessGate {
    private data class CachedStatus(
        val suspended: Boolean,
        val expiresAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, CachedStatus>()

    override fun isSuspended(tenantId: String): Boolean {
        val now = Instant.now()
        cache[tenantId]?.let { if (it.expiresAt.isAfter(now)) return it.suspended }
        val suspended = lookup(tenantId)
        cache[tenantId] = CachedStatus(suspended, now.plus(TTL))
        return suspended
    }

    fun invalidate(tenantId: String) {
        cache.remove(tenantId)
    }

    private fun lookup(tenantId: String): Boolean {
        val id = runCatching { UUID.fromString(tenantId) }.getOrNull() ?: return false
        val tenant = tenants.findById(id).orElse(null) ?: return false
        return tenant.status == TenantStatus.SUSPENDED
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(30)
    }
}
