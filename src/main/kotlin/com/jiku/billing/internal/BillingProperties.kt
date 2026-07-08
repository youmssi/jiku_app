package com.jiku.billing.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Pricing configuration (JIKU-32). The free-tier allowance and the paid tiers are
 * configuration, not literals scattered through the code, so pricing changes
 * without a code change. Tiers are consumed here for naming and by the payment
 * flow (JIKU-33) for amounts.
 */
@ConfigurationProperties(prefix = "billing")
data class BillingProperties(
    /** Distinct invited guests allowed per event before a paid tier is required. */
    val freeTierGuests: Long = 100,
    /** ISO-4217 currency the paid tiers are priced in. */
    val currency: String = "XOF",
    /** Paid tiers, ascending by allowance. Each unlocks up to [Tier.maxGuests]. */
    val tiers: List<Tier> =
        listOf(
            Tier(name = "STANDARD", maxGuests = 2_000, priceMinor = 5_000_00),
            Tier(name = "PREMIUM", maxGuests = 10_000, priceMinor = 20_000_00),
        ),
) {
    data class Tier(
        val name: String,
        /** Inclusive upper bound of invited guests this tier unlocks. */
        val maxGuests: Long,
        /** Price in the currency's minor unit (e.g. centimes for XOF). */
        val priceMinor: Long,
    )

    /** Tier name for a given number of invited guests (usage volume). */
    fun tierForUsage(invitedGuests: Long): String =
        if (invitedGuests <= freeTierGuests) {
            FREE_TIER
        } else {
            tiers.firstOrNull { invitedGuests <= it.maxGuests }?.name ?: tiers.last().name
        }

    /** The smallest paid tier that unlocks at least [invitedGuests], if any. */
    fun tierForAllowance(invitedGuests: Long): Tier? = tiers.firstOrNull { invitedGuests <= it.maxGuests } ?: tiers.lastOrNull()

    companion object {
        const val FREE_TIER = "FREE"
    }
}
