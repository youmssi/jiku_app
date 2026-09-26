package com.jiku.tenant.internal

import com.jiku.shared.TenantCurrency
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class TenantCurrencyAdapter(
    private val tenants: TenantRepository,
) : TenantCurrency {
    @Transactional(readOnly = true)
    override fun of(tenantId: String): String =
        tenants
            .findById(UUID.fromString(tenantId))
            .orElseThrow { IllegalStateException("Tenant $tenantId does not exist") }
            .currency
}
