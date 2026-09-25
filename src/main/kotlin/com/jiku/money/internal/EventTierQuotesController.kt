package com.jiku.money.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The tiers an event can still buy and what each costs it (ADR 105), priced
 * by the same quote a payment request charges, so the page never has to
 * rebuild the tier difference or the interactive surcharge itself.
 */
@RestController
@PreAuthorize("hasRole('ORGANIZER')")
class EventTierQuotesController(
    private val eventPricing: EventPricing,
) {
    @GetMapping("/events/{eventId}/billing/quotes")
    fun quotes(
        @PathVariable eventId: UUID,
    ): List<EventTierQuote> = eventPricing.tierQuotes(eventId)
}
