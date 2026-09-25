package com.jiku.money.internal

import com.jiku.catalog.DeliveryMode
import com.jiku.catalog.EventModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * What an event costs the organization (ADR 105, decision 3), in its billing
 * currency. Moving an event up a tier charges only the difference with the
 * tier it already paid for. An event whose guests answer in WhatsApp adds the
 * interactive surcharge for each paid guest not yet covered for it, so an
 * event that switches to that mode after paying can buy just the surcharge.
 */
@Service
class EventPricing(
    private val properties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val usageRecords: UsageRecordRepository,
    private val billingCurrency: TenantBillingCurrency,
    private val events: EventModuleApi,
) {
    @Transactional(readOnly = true)
    fun upgradeQuote(
        eventId: UUID,
        tier: BillingProperties.Tier,
    ): EventQuote {
        val currency = billingCurrency.current()
        val record = usageRecords.findByEventId(eventId)
        val unlocked = record?.unlockedAllowance ?: properties.freeTierGuests
        val interactive = events.findEvent(eventId)?.deliveryMode == DeliveryMode.INTERACTIVE
        val uncovered = if (interactive) (tier.maxGuests - (record?.interactiveAllowance ?: 0)).coerceAtLeast(0) else 0
        if (unlocked >= tier.maxGuests && uncovered == 0L) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This event already allows ${tier.maxGuests} guests or more")
        }
        val tierAmount =
            if (unlocked >= tier.maxGuests) {
                0
            } else {
                val paid = platformSettings.tiers().filter { it.maxGuests <= unlocked }.maxByOrNull { it.maxGuests }
                (tier.price.amountMinor(currency) - (paid?.price?.amountMinor(currency) ?: 0)).coerceAtLeast(0)
            }
        return EventQuote(
            amountMinor = tierAmount + uncovered * properties.interactivePerGuest.amountMinor(currency),
            currency = currency,
            interactive = interactive,
        )
    }

    /**
     * Price of an event of [guests] beyond the last tier: that tier plus each
     * guest above it, and the interactive surcharge on every guest when asked.
     */
    @Transactional(readOnly = true)
    fun beyondQuote(
        guests: Long,
        interactive: Boolean = false,
    ): EventQuote {
        val currency = billingCurrency.current()
        val last = platformSettings.tiers().maxBy { it.maxGuests }
        val extra = (guests - last.maxGuests).coerceAtLeast(0)
        val surcharge = if (interactive) maxOf(guests, last.maxGuests) * properties.interactivePerGuest.amountMinor(currency) else 0
        return EventQuote(
            amountMinor = last.price.amountMinor(currency) + extra * properties.beyondPerGuest.amountMinor(currency) + surcharge,
            currency = currency,
            interactive = interactive,
        )
    }
}

data class EventQuote(
    val amountMinor: Long,
    val currency: String,
    /** Includes the interactive WhatsApp surcharge. */
    val interactive: Boolean = false,
)
