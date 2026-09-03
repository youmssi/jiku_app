package com.jiku.money.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Pricing configuration (JIKU-32, regraded to the Guinea market grid in JIKU-53).
 * The free-tier allowance and the paid tiers are configuration, not literals
 * scattered through the code, so pricing changes without a code change. Tiers are
 * consumed here for naming and by the payment flow (JIKU-33) for amounts.
 */
@ConfigurationProperties(prefix = "billing")
data class BillingProperties(
    /** Distinct invited guests allowed cumulatively per tenant account before a paid tier is required (JIKU-54). */
    val freeTierGuests: Long = 100,
    /**
     * Rolling window over which [freeTierGuests] accumulates, per tenant
     * (JIKU-54). A fixed-length window (365 days) rather than a calendar-month
     * one, matching how [Duration] arithmetic on an [java.time.Instant] works —
     * the same approximation product analytics tools generally make for
     * "rolling 12 months".
     */
    val freeTierWindow: Duration = Duration.ofDays(365),
    /** ISO-4217 currency the paid tiers are priced in. GNF has no minor unit. */
    val currency: String = "GNF",
    /** Fixed-price paid tiers, ascending by allowance. Each unlocks up to [Tier.maxGuests]. */
    val tiers: List<Tier> =
        listOf(
            Tier(name = "BRONZE", maxGuests = 300, priceMinor = 150_000),
            Tier(name = "ARGENT", maxGuests = 600, priceMinor = 300_000),
            Tier(name = "OR", maxGuests = 1_000, priceMinor = 500_000),
        ),
    /** Pricing for usage beyond the last fixed [tiers] entry — see [CustomTierPricing]. */
    val custom: CustomTierPricing = CustomTierPricing(),
) {
    data class Tier(
        val name: String,
        /** Inclusive upper bound of invited guests this tier unlocks. */
        val maxGuests: Long,
        /** Price in [currency]'s minor unit (GNF has none, so this is the full amount). */
        val priceMinor: Long,
    )

    /**
     * CUSTOM tier pricing: open-ended usage beyond the last fixed [Tier], so it
     * cannot be a flat price. Quoted in USD cents (the market's source pricing
     * unit) and converted through a configurable rate — both the dollar amounts
     * and the exchange rate move independently of a release, so neither is a
     * literal.
     */
    data class CustomTierPricing(
        val perGuestUsdCents: Long = 5,
        val setupFeeUsdCents: Long = 1_500,
        val usdToGnfRate: Long = 8_760,
    ) {
        /** Total price in GNF for [guestCount] guests: variable sending + fixed setup. */
        fun priceGnf(guestCount: Long): Long {
            val totalUsdCents = perGuestUsdCents * guestCount + setupFeeUsdCents
            return totalUsdCents * usdToGnfRate / 100
        }
    }

    /** Tier name for a given number of invited guests (usage volume). */
    fun tierForUsage(invitedGuests: Long): String =
        when {
            invitedGuests <= freeTierGuests -> FREE_TIER
            else -> tiers.firstOrNull { invitedGuests <= it.maxGuests }?.name ?: CUSTOM_TIER
        }

    /** The smallest fixed-price tier that unlocks at least [invitedGuests]; null beyond the last tier (CUSTOM pricing applies instead). */
    fun tierForAllowance(invitedGuests: Long): Tier? = tiers.firstOrNull { invitedGuests <= it.maxGuests }

    companion object {
        const val FREE_TIER = "FREE"
        const val CUSTOM_TIER = "CUSTOM"
    }
}
