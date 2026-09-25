package com.jiku.money.internal

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * What an event costs the organization (ADR 105, decision 3), in its billing
 * currency. Moving an event up a tier charges only the difference with the
 * tier it already paid for.
 */
@Service
class EventPricing(
    private val properties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val usageRecords: UsageRecordRepository,
    private val billingCurrency: TenantBillingCurrency,
) {
    @Transactional(readOnly = true)
    fun upgradeQuote(
        eventId: UUID,
        tier: BillingProperties.Tier,
    ): EventQuote {
        val currency = billingCurrency.current()
        val unlocked = usageRecords.findByEventId(eventId)?.unlockedAllowance ?: properties.freeTierGuests
        if (unlocked >= tier.maxGuests) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This event already allows ${tier.maxGuests} guests or more")
        }
        val paid = platformSettings.tiers().filter { it.maxGuests <= unlocked }.maxByOrNull { it.maxGuests }
        val amount = tier.price.amountMinor(currency) - (paid?.price?.amountMinor(currency) ?: 0)
        return EventQuote(amountMinor = amount.coerceAtLeast(0), currency = currency)
    }

    /** Price of an event of [guests] beyond the last tier: that tier plus each guest above it. */
    @Transactional(readOnly = true)
    fun beyondQuote(guests: Long): EventQuote {
        val currency = billingCurrency.current()
        val last = platformSettings.tiers().maxBy { it.maxGuests }
        val extra = (guests - last.maxGuests).coerceAtLeast(0)
        return EventQuote(
            amountMinor = last.price.amountMinor(currency) + extra * properties.beyondPerGuest.amountMinor(currency),
            currency = currency,
        )
    }
}

data class EventQuote(
    val amountMinor: Long,
    val currency: String,
)
