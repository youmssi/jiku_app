package com.jiku.tenant.internal

import com.jiku.shared.TenantBrand
import com.jiku.shared.TenantBrandInfo
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class TenantBrandAdapter(
    private val tenants: TenantRepository,
) : TenantBrand {
    @Transactional(readOnly = true)
    override fun of(tenantId: String): TenantBrandInfo? =
        tenants
            .findById(runCatching { UUID.fromString(tenantId) }.getOrNull() ?: return null)
            .map { TenantBrandInfo(name = it.effectiveDisplayName(), logoUrl = it.branding?.logoUrl) }
            .orElse(null)
}
