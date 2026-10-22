package com.jiku.money.internal

import com.jiku.invitation.InvitationModuleApi
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * The single place a paid purchase raises an event's paid guests. Both
 * confirmation paths — the provider's server-to-server callback (JIKU-33) and an
 * admin confirming a manual payment (JIKU-41) — go through here, so they cannot
 * drift apart.
 */
@Service
class EventGuestsUnlock(
    private val usageRecords: UsageRecordRepository,
    private val eventPricing: EventPricing,
    private val tenantQuota: TenantQuotaService,
    private val invitation: InvitationModuleApi,
) {
    /**
     * Raises the event's paid guests to [guests] (never lowers them). Guests it
     * already sent for free stay counted for good against the tenant's
     * cumulative budget (JIKU-54) — paying does not reverse them. On the first
     * purchase the free guests the price left out (JIKU-270) are taken from
     * that budget now, so no other event can use them too.
     */
    fun unlock(
        eventId: UUID,
        guests: Long,
    ) {
        val record = usageRecords.findByEventId(eventId) ?: UsageRecord(eventId = eventId)
        if (record.paidGuests == 0L) {
            val invited = invitation.guestStats(eventId).invited
            tenantQuota.recordFreeCommitment(minOf(eventPricing.freeGuests(eventId), guests) - invited)
        }
        record.paidGuests = maxOf(record.paidGuests, guests)
        record.updatedAt = Instant.now()
        usageRecords.save(record)
    }
}
