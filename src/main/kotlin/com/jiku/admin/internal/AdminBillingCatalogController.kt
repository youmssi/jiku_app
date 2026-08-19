package com.jiku.admin.internal

import com.jiku.billing.BillingModuleApi
import com.jiku.billing.BillingTierOption
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The pricing grid as the back-office needs to see it (JIKU-66). The organizer's
 * own catalog endpoint is scoped to `ORGANIZER`, which a platform administrator
 * deliberately never holds — so the operator surfaces read the same configured
 * tiers through the billing module's API here, rather than shipping a second,
 * hardcoded copy of the grid that drifts the next time pricing changes.
 */
@RestController
@RequestMapping("/admin/billing/tiers")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminBillingCatalogController(
    private val billing: BillingModuleApi,
) {
    @GetMapping
    fun catalog(): AdminTierCatalog = AdminTierCatalog(currency = billing.currency(), tiers = billing.tierOptions())
}

data class AdminTierCatalog(
    val currency: String,
    val tiers: List<BillingTierOption>,
)
