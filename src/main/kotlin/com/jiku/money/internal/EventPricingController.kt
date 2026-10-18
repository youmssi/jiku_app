package com.jiku.money.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Event pricing for the organizer (JIKU-268): the per-guest price in the
 * organization's billing currency, and what raising one event to a number of
 * guests would cost, priced by the same quote a payment request charges.
 */
@RestController
@PreAuthorize("hasRole('ORGANIZER')")
class EventPricingController(
    private val properties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val billingCurrency: TenantBillingCurrency,
    private val eventPricing: EventPricing,
) {
    @GetMapping("/billing/event-pricing")
    @Transactional(readOnly = true)
    fun pricing(): EventPricingView {
        val currency = billingCurrency.current()
        return EventPricingView(
            currency = currency,
            freeTierGuests = properties.freeTierGuests,
            perGuestMinor = platformSettings.perGuest().amountMinor(currency),
            maxGuestsPerEvent = properties.maxGuestsPerEvent,
        )
    }

    @GetMapping("/events/{eventId}/billing/quote")
    fun quote(
        @PathVariable eventId: UUID,
        @RequestParam guests: Long,
    ): EventQuote = eventPricing.quote(eventId, guests)
}

data class EventPricingView(
    /** Currency every price below is in: GNF, XOF, XAF or USD (minor units). */
    val currency: String,
    /** Guests an organization gets free each year across its events. */
    val freeTierGuests: Long,
    /** Tax-inclusive price of one guest, every delivery mode included. */
    val perGuestMinor: Long,
    val maxGuestsPerEvent: Long,
)
