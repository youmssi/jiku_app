package com.jiku.billing.internal

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * The single place a paid tier raises an event's unlocked allowance. Both
 * confirmation paths — the provider's server-to-server callback (JIKU-33) and an
 * admin confirming a manual payment (JIKU-41) — go through here, so they cannot
 * drift apart.
 */
@Service
class TierUnlockService(
    private val usageRecords: UsageRecordRepository,
    private val billingProperties: BillingProperties,
) {
    /**
     * Raises the event's unlocked allowance to the paid tier (never lowers it).
     * Any guests it already sent for free stay counted for good against the
     * tenant's cumulative budget (JIKU-54) — unlocking does not reverse them.
     */
    fun unlock(
        eventId: UUID,
        tierName: String,
    ) {
        val tier = billingProperties.tiers.firstOrNull { it.name == tierName } ?: return
        val record =
            usageRecords.findByEventId(eventId)
                ?: UsageRecord(eventId = eventId, unlockedAllowance = billingProperties.freeTierGuests)
        record.unlockedAllowance = maxOf(record.unlockedAllowance, tier.maxGuests)
        record.updatedAt = Instant.now()
        usageRecords.save(record)
    }
}
