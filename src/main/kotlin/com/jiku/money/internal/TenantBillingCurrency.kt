package com.jiku.money.internal

import com.jiku.money.PriceList
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Component
import java.util.UUID

/** The currency Jikū bills the current organization in (ADR 105): its own franc, or US dollars. */
@Component
class TenantBillingCurrency(
    private val tenants: TenantModuleApi,
) {
    fun current(): String {
        val tenantId = requireNotNull(TenantContext.get()) { "Billing requires an authenticated tenant" }
        val organizationCurrency = tenants.findTenant(UUID.fromString(tenantId))?.currency ?: PriceList.GNF
        return PriceList.billingCurrencyFor(organizationCurrency)
    }
}
