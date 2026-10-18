package com.jiku.money.internal

import com.jiku.money.PriceList
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Event pricing (JIKU-268). An organization gets [freeTierGuests] guests a
 * year for free; past that an event buys its guests at [perGuest] each, every
 * delivery mode included. Prices are tax-inclusive and set in GNF, FCFA and
 * USD, so pricing changes without a code change.
 */
@ConfigurationProperties(prefix = "billing")
data class BillingProperties(
    /** Distinct invited guests allowed cumulatively per tenant account before an event must buy its guests (JIKU-54). */
    val freeTierGuests: Long = 100,
    /**
     * Rolling window over which [freeTierGuests] accumulates, per tenant
     * (JIKU-54). A fixed-length window (365 days) rather than a calendar-month
     * one, matching how [Duration] arithmetic on an [java.time.Instant] works —
     * the same approximation product analytics tools generally make for
     * "rolling 12 months".
     */
    val freeTierWindow: Duration = Duration.ofDays(365),
    /** Price of one guest of an event, all delivery modes included. */
    val perGuest: PriceList = PriceList(750, 50, 8),
    /** Most guests one event purchase may cover, a guard against a mistyped figure. */
    val maxGuestsPerEvent: Long = 100_000,
    /** Pro Organisateur (the Organizer Pack in code), for planners, agencies and venues. */
    val pack: Pack = Pack(),
    /** Sending from the organization's own WhatsApp number (ADR 105). */
    val ownWhatsAppNumber: OwnWhatsAppNumber = OwnWhatsAppNumber(),
) {
    /**
     * The own-number add-on's monthly price; the plans in [includedPlans] and
     * an active Pro Organisateur include it.
     */
    data class OwnWhatsAppNumber(
        val monthly: PriceList = PriceList(100_000, 7_000, 1_200),
        val includedPlans: List<String> = listOf("Services Pro"),
    )

    /**
     * A monthly price for [includedGuests] guests a month across every event,
     * all delivery modes included. More guests are bought ahead in blocks of
     * [extraBlockGuests] at [extraPerGuest] each; unused guests do not carry over.
     */
    data class Pack(
        val monthly: PriceList = PriceList(600_000, 40_000, 7_000),
        val includedGuests: Long = 1_000,
        val extraPerGuest: PriceList = PriceList(600, 40, 7),
        val extraBlockGuests: Long = 100,
    )

    companion object {
        /** An event within the organization's free guests. */
        const val FREE_TIER = "FREE"

        /** An event that bought its guests. */
        const val PAID_TIER = "PAID"

        /** An event covered by Pro Organisateur. */
        const val PACK_TIER = "PACK"
    }
}
