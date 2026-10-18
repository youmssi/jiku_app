package com.jiku.money.internal

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * What an event's guests cost the organization (JIKU-268), in its billing
 * currency: the per-guest price times the guests it buys beyond those it has
 * already paid for, every delivery mode included.
 */
@Service
class EventPricing(
    private val properties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val usageRecords: UsageRecordRepository,
    private val billingCurrency: TenantBillingCurrency,
    private val organizerPack: OrganizerPackService,
) {
    /** Price of raising [eventId] to [guests] paid guests. */
    @Transactional(readOnly = true)
    fun quote(
        eventId: UUID,
        guests: Long,
    ): EventQuote {
        if (organizerPack.isActive()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Pro Organisateur already covers this event")
        }
        if (guests < 1 || guests > properties.maxGuestsPerEvent) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose between 1 and ${properties.maxGuestsPerEvent} guests")
        }
        val paid = usageRecords.findByEventId(eventId)?.paidGuests ?: 0
        if (guests <= paid) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This event already has $paid paid guests")
        }
        val currency = billingCurrency.current()
        return EventQuote(
            guests = guests,
            paidGuests = paid,
            amountMinor = (guests - paid) * platformSettings.perGuest().amountMinor(currency),
            currency = currency,
        )
    }
}

/** The price of bringing an event to [guests] paid guests, of which [paidGuests] are already paid. */
data class EventQuote(
    val guests: Long,
    val paidGuests: Long,
    val amountMinor: Long,
    val currency: String,
)
