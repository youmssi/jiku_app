package com.jiku.money.internal

import com.jiku.invitation.InvitationModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * What an event's guests cost the organization (JIKU-268), in its billing
 * currency: the per-guest price times the guests it buys beyond those already
 * covered, every delivery mode included. On its first purchase an event is
 * covered by the free guests it may still use (JIKU-270), so 120 guests with
 * the 100 free guests untouched cost 20.
 */
@Service
class EventPricing(
    private val properties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val usageRecords: UsageRecordRepository,
    private val billingCurrency: TenantBillingCurrency,
    private val organizerPack: OrganizerPackService,
    private val tenantQuota: TenantQuotaService,
    private val invitation: InvitationModuleApi,
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
        val free = if (paid > 0) 0 else freeGuests(eventId)
        val covered = maxOf(paid, free)
        if (guests <= covered) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This event already covers $covered guests")
        }
        val currency = billingCurrency.current()
        return EventQuote(
            guests = guests,
            paidGuests = paid,
            freeGuests = free,
            amountMinor = (guests - covered) * platformSettings.perGuest().amountMinor(currency),
            currency = currency,
        )
    }

    /** The free guests an event that bought none may reach: its own plus what the organization has left. */
    fun freeGuests(eventId: UUID): Long = tenantQuota.freeCeilingFor(invitation.guestStats(eventId).invited)
}

/**
 * The price of bringing an event to [guests] guests, of which [paidGuests] are
 * already paid and [freeGuests] come from the organization's free guests.
 */
data class EventQuote(
    val guests: Long,
    val paidGuests: Long,
    val freeGuests: Long,
    val amountMinor: Long,
    val currency: String,
)
