package com.jiku.billing.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The paid tiers an organizer can purchase (JIKU-35 purchase UI). Served from
 * configuration so the frontend never hardcodes prices or allowances.
 */
@RestController
@RequestMapping("/billing/tiers")
@PreAuthorize("hasRole('ORGANIZER_ADMIN')")
class BillingTiersController(
    private val properties: BillingProperties,
) {
    @GetMapping
    fun tiers(): TierCatalog =
        TierCatalog(
            currency = properties.currency,
            freeTierGuests = properties.freeTierGuests,
            tiers =
                properties.tiers.map {
                    TierOption(name = it.name, maxGuests = it.maxGuests, priceMinor = it.priceMinor)
                },
        )
}

data class TierCatalog(
    val currency: String,
    val freeTierGuests: Long,
    val tiers: List<TierOption>,
)

data class TierOption(
    val name: String,
    val maxGuests: Long,
    val priceMinor: Long,
)
