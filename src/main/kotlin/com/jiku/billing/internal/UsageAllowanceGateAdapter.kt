package com.jiku.billing.internal

import com.jiku.shared.UsageAllowanceGate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Billing's implementation of the shared paywall gate (JIKU-34). Reports an event's
 * unlocked allowance ceiling straight from its own [UsageRecord] (or the free-tier
 * default) — no cross-module read, so no dependency cycle with invitation.
 */
@Component
class UsageAllowanceGateAdapter(
    private val usageRecords: UsageRecordRepository,
    private val properties: BillingProperties,
) : UsageAllowanceGate {
    @Transactional(readOnly = true)
    override fun allowanceCeiling(eventId: UUID): Long = usageRecords.findByEventId(eventId)?.unlockedAllowance ?: properties.freeTierGuests
}
