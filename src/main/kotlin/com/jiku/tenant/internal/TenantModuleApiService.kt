package com.jiku.tenant.internal

import com.jiku.tenant.TenantDirectoryEntry
import com.jiku.tenant.TenantDirectoryPage
import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantModuleApi
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TenantModuleApiService(
    private val tenants: TenantRepository,
    private val memberships: OrganizerMembershipRepository,
    private val accessGate: TenantAccessGateAdapter,
) : TenantModuleApi {
    @Transactional(readOnly = true)
    override fun findTenant(tenantId: UUID): TenantInfo? = tenants.findById(tenantId).map { it.toTenantInfo() }.orElse(null)

    @Transactional(readOnly = true)
    override fun searchTenants(
        query: String?,
        page: Int,
        size: Int,
    ): TenantDirectoryPage {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt"))
        val result =
            if (query.isNullOrBlank()) {
                tenants.findAll(pageable)
            } else {
                tenants.search(query.trim(), pageable)
            }
        return TenantDirectoryPage(
            entries =
                result.content.map { tenant ->
                    val id = requireNotNull(tenant.id)
                    TenantDirectoryEntry(
                        id = id,
                        name = tenant.name,
                        contactEmail = tenant.contactEmail,
                        status = tenant.status.name,
                        createdAt = tenant.createdAt,
                        organizerCount = memberships.countByTenantId(id.toString()),
                    )
                },
            total = result.totalElements,
            page = result.number,
            size = result.size,
        )
    }

    @Transactional
    override fun setTenantSuspended(
        tenantId: UUID,
        suspended: Boolean,
    ): TenantInfo? {
        val tenant = tenants.findById(tenantId).orElse(null) ?: return null
        tenant.status = if (suspended) TenantStatus.SUSPENDED else TenantStatus.ACTIVE
        val saved = tenants.save(tenant)
        // The gate caches status lookups; a status change must be visible on the
        // very next request, not after the cache entry ages out.
        accessGate.invalidate(tenantId.toString())
        return saved.toTenantInfo()
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
