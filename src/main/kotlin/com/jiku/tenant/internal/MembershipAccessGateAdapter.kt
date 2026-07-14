package com.jiku.tenant.internal

import com.jiku.shared.MembershipAccessGate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tenant-module implementation of the membership check (JIKU-50), mirroring
 * [TenantAccessGateAdapter]: consulted on every tenant-bound request, so lookups
 * are cached briefly and removing a member invalidates their entry immediately.
 */
@Component
class MembershipAccessGateAdapter(
    private val memberships: OrganizerMembershipRepository,
) : MembershipAccessGate {
    private data class CachedResult(
        val member: Boolean,
        val expiresAt: Instant,
    )

    private val cache = ConcurrentHashMap<String, CachedResult>()

    override fun isMember(
        userId: String,
        tenantId: String,
    ): Boolean {
        val key = "$userId:$tenantId"
        val now = Instant.now()
        cache[key]?.let { if (it.expiresAt.isAfter(now)) return it.member }
        val member = lookup(userId, tenantId)
        cache[key] = CachedResult(member, now.plus(TTL))
        return member
    }

    fun invalidate(
        userId: String,
        tenantId: String,
    ) {
        cache.remove("$userId:$tenantId")
    }

    private fun lookup(
        userId: String,
        tenantId: String,
    ): Boolean {
        val id = runCatching { UUID.fromString(userId) }.getOrNull() ?: return false
        return memberships.findByUserIdAndTenantId(id, tenantId) != null
    }

    private companion object {
        val TTL: Duration = Duration.ofSeconds(30)
    }
}
