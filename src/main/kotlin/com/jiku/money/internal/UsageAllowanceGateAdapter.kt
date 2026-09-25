package com.jiku.money.internal

import com.jiku.shared.UsageAllowanceGate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Billing's implementation of the shared paywall gate (JIKU-34). An event that has
 * been unlocked to a paid tier reports its own [UsageRecord] ceiling directly; one
 * still on the free tier defers to the tenant's cumulative budget (JIKU-54), never
 * a per-event default — no cross-module read either way, so no dependency cycle
 * with invitation.
 */
@Component
class UsageAllowanceGateAdapter(
    private val usageRecords: UsageRecordRepository,
    private val properties: BillingProperties,
    private val tenantQuota: TenantQuotaService,
) : UsageAllowanceGate {
    @Transactional(readOnly = true)
    override fun allowanceCeiling(
        eventId: UUID,
        alreadyCommitted: Long,
    ): Long {
        val paidAllowance = usageRecords.findByEventId(eventId)?.unlockedAllowance
        if (paidAllowance != null && paidAllowance > properties.freeTierGuests) {
            return paidAllowance
        }
        return tenantQuota.freeCeilingFor(alreadyCommitted)
    }

    override fun recordCommitment(
        eventId: UUID,
        newGuestCount: Long,
    ) {
        val paidAllowance = usageRecords.findByEventId(eventId)?.unlockedAllowance
        if (paidAllowance != null && paidAllowance > properties.freeTierGuests) {
            return
        }
        tenantQuota.recordFreeCommitment(newGuestCount)
    }

    @Transactional(readOnly = true)
    override fun interactiveCovered(eventId: UUID): Boolean {
        val record = usageRecords.findByEventId(eventId) ?: return true
        return record.unlockedAllowance <= properties.freeTierGuests || record.interactiveAllowance >= record.unlockedAllowance
    }
}
