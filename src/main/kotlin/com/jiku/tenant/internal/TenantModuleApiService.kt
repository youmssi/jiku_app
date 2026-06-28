package com.jiku.tenant.internal

import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TenantModuleApiService(
    private val tenants: TenantRepository,
) : TenantModuleApi {
    @Transactional(readOnly = true)
    override fun findTenant(tenantId: UUID): TenantInfo? =
        tenants
            .findById(tenantId)
            .map { tenant ->
                TenantInfo(
                    id = requireNotNull(tenant.id),
                    name = tenant.name,
                    contactEmail = tenant.contactEmail,
                    status = tenant.status.name,
                    createdAt = tenant.createdAt,
                )
            }.orElse(null)
}
