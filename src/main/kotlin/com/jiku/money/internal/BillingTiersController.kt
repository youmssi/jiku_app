package com.jiku.money.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The event tiers an organizer can buy (JIKU-35), priced in the organization's
 * billing currency (ADR 105). Served from configuration so the frontend never
 * hardcodes prices or allowances.
 */
@RestController
@RequestMapping("/billing/tiers")
@PreAuthorize("hasRole('ORGANIZER')")
class BillingTiersController(
    private val properties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val billingCurrency: TenantBillingCurrency,
    private val eventPricing: EventPricing,
) {
    @GetMapping
    fun tiers(): TierCatalog {
        val currency = billingCurrency.current()
        val beyond = properties.beyondPerGuest.amountMinor(currency)
        return TierCatalog(
            currency = currency,
            freeTierGuests = properties.freeTierGuests,
            tiers =
                platformSettings.tiers().map {
                    TierOption(name = it.name, maxGuests = it.maxGuests, priceMinor = it.price.amountMinor(currency))
                },
            interactivePerGuestMinor = properties.interactivePerGuest.amountMinor(currency),
            custom =
                CustomTierOption(
                    beyondPerGuestMinor = beyond,
                    perGuestUsdCents = properties.beyondPerGuest.usdCents,
                    setupFeeUsdCents = 0,
                ),
        )
    }

    @GetMapping("/custom-quote")
    fun customQuote(
        @RequestParam guestCount: Long,
        @RequestParam(defaultValue = "false") interactive: Boolean,
    ): CustomQuote {
        val quote = eventPricing.beyondQuote(guestCount, interactive)
        return CustomQuote(guestCount = guestCount, priceMinor = quote.amountMinor, currency = quote.currency)
    }
}

data class TierCatalog(
    /** Currency every price below is in: GNF, XOF, XAF or USD (minor units). */
    val currency: String,
    val freeTierGuests: Long,
    val tiers: List<TierOption>,
    /** Added per paid guest when the event's guests answer in WhatsApp (ADR 105); the free tier includes it. */
    val interactivePerGuestMinor: Long,
    val custom: CustomTierOption,
)

data class TierOption(
    val name: String,
    val maxGuests: Long,
    val priceMinor: Long,
)

/** Pricing beyond the last tier: its price plus [beyondPerGuestMinor] for each guest above it. */
data class CustomTierOption(
    val beyondPerGuestMinor: Long,
    @Deprecated("Use beyondPerGuestMinor")
    val perGuestUsdCents: Long,
    @Deprecated("There is no setup fee since ADR 105; always 0")
    val setupFeeUsdCents: Long,
)

data class CustomQuote(
    val guestCount: Long,
    val priceMinor: Long,
    val currency: String,
)
