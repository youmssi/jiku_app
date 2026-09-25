package com.jiku.money.internal

import com.jiku.money.PriceList
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Event pricing (JIKU-32, re-based in ADR 105). The free allowance, the fixed
 * tiers and the per-guest price beyond the last tier are configuration, each
 * price set in GNF, FCFA and USD, so pricing changes without a code change.
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
    /** Fixed-price paid tiers, ascending by allowance. Each unlocks up to [Tier.maxGuests]. */
    val tiers: List<Tier> =
        listOf(
            Tier(name = "BRONZE", maxGuests = 300, price = PriceList(225_000, 15_000, 2_500)),
            Tier(name = "ARGENT", maxGuests = 600, price = PriceList(375_000, 25_000, 4_500)),
            Tier(name = "OR", maxGuests = 1_000, price = PriceList(600_000, 40_000, 7_000)),
        ),
    /** Price of each guest beyond the last fixed tier, added to that tier's price. */
    val beyondPerGuest: PriceList = PriceList(500, 35, 6),
    /**
     * Added per guest of a paid tier when the event's guests answer in WhatsApp
     * (ADR 105, INTERACTIVE mode). The free tier includes every mode.
     */
    val interactivePerGuest: PriceList = PriceList(150, 10, 2),
) {
    data class Tier(
        val name: String,
        /** Inclusive upper bound of invited guests this tier unlocks. */
        val maxGuests: Long,
        val price: PriceList,
    )

    /** Tier name for a given number of invited guests (usage volume). */
    fun tierForUsage(invitedGuests: Long): String =
        when {
            invitedGuests <= freeTierGuests -> FREE_TIER
            else -> tiers.firstOrNull { invitedGuests <= it.maxGuests }?.name ?: CUSTOM_TIER
        }

    /** The smallest fixed-price tier that unlocks at least [invitedGuests]; null beyond the last tier. */
    fun tierForAllowance(invitedGuests: Long): Tier? = tiers.firstOrNull { invitedGuests <= it.maxGuests }

    companion object {
        const val FREE_TIER = "FREE"
        const val CUSTOM_TIER = "CUSTOM"
    }
}
