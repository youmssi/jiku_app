package com.jiku.billing.internal

import com.jiku.billing.BillingAllowance
import com.jiku.invitation.InvitationModuleApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Computes and persists per-event usage (JIKU-32). Usage counts are always derived
 * live from the invitation module's authoritative figures — so importing and
 * sending across multiple batches can never double-count or reset — while the
 * unlocked allowance is the stateful entitlement stored on the [UsageRecord]
 * (free tier by default, raised by a paid unlock in JIKU-33).
 *
 * Reading the allowance also refreshes the persisted snapshot, so the record stays
 * an accurate, auditable reflection of current usage without a separate job.
 */
@Service
class UsageService(
    private val usageRecords: UsageRecordRepository,
    private val invitation: InvitationModuleApi,
    private val properties: BillingProperties,
    private val trialService: TrialService,
    private val tenantQuota: TenantQuotaService,
) {
    @Transactional
    fun allowance(eventId: UUID): BillingAllowance {
        val stats = invitation.guestStats(eventId)
        val sent = invitation.sentInvitationCounts(eventId)

        val record =
            usageRecords.findByEventId(eventId)
                ?: UsageRecord(eventId = eventId, unlockedAllowance = properties.freeTierGuests)
        record.guestsImported = stats.total
        record.invitedGuests = stats.invited
        record.invitationsSentEmail = sent.email
        record.invitationsSentWhatsapp = sent.whatsapp
        record.updatedAt = Instant.now()
        usageRecords.save(record)

        return record.toAllowance(effectiveAllowance(eventId, record.invitedGuests, record.unlockedAllowance))
    }

    /**
     * Adds [amountMinor] to [eventId]'s prepaid credit (JIKU-57) — a booking
     * deposit or balance already paid outside the normal payment flow, to be
     * netted off the next manual payment request rather than charged twice.
     */
    @Transactional
    fun recordPrepayment(
        eventId: UUID,
        amountMinor: Long,
    ) {
        if (amountMinor <= 0) return
        val record =
            usageRecords.findByEventId(eventId)
                ?: UsageRecord(eventId = eventId, unlockedAllowance = properties.freeTierGuests)
        record.prepaidAmountMinor += amountMinor
        record.updatedAt = Instant.now()
        usageRecords.save(record)
    }

    /**
     * Spends up to [tierPriceMinor] of [eventId]'s prepaid credit and returns
     * the discounted price to actually charge (JIKU-57). The credit is zeroed
     * the moment it is applied — a booking's deposit is meant to offset the
     * *next* upgrade this event needs, not every future one.
     */
    @Transactional
    fun applyPrepaymentDiscount(
        eventId: UUID,
        tierPriceMinor: Long,
    ): Long {
        val record = usageRecords.findByEventId(eventId) ?: return tierPriceMinor
        val discount = minOf(record.prepaidAmountMinor, tierPriceMinor)
        if (discount <= 0) return tierPriceMinor
        record.prepaidAmountMinor -= discount
        record.updatedAt = Instant.now()
        usageRecords.save(record)
        return tierPriceMinor - discount
    }

    @Transactional(readOnly = true)
    fun canInvite(
        eventId: UUID,
        additionalGuests: Long,
    ): Boolean {
        val invited = invitation.guestStats(eventId).invited
        val unlocked = usageRecords.findByEventId(eventId)?.unlockedAllowance ?: properties.freeTierGuests
        return invited + additionalGuests.coerceAtLeast(0) <= effectiveAllowance(eventId, invited, unlocked)
    }

    /**
     * The ceiling actually in force: while still on the free tier, the tenant's
     * cumulative budget (JIKU-54) — computed from [alreadyCommitted], this
     * event's own already-invited guests, so that contribution is never
     * subtracted twice — rather than a per-event default; once unlocked to a
     * paid tier, that stored entitlement. Either way, raised — never replaced —
     * by a live trial (JIKU-42). Keeping the paid allowance stored and the
     * trial contribution computed means trial expiry needs no write here.
     */
    private fun effectiveAllowance(
        eventId: UUID,
        alreadyCommitted: Long,
        paidAllowance: Long,
    ): Long {
        val base = if (paidAllowance > properties.freeTierGuests) paidAllowance else tenantQuota.freeCeilingFor(alreadyCommitted)
        return maxOf(base, trialService.liveTrialAllowance(eventId) ?: 0)
    }

    private fun UsageRecord.toAllowance(effectiveAllowance: Long): BillingAllowance {
        val remaining = (effectiveAllowance - invitedGuests).coerceAtLeast(0)
        return BillingAllowance(
            invitedGuests = invitedGuests,
            allowance = effectiveAllowance,
            remaining = remaining,
            // "Within" means usage has not exceeded the ceiling (exactly at it still
            // counts as within); whether more can be sent is a separate check.
            withinAllowance = invitedGuests <= effectiveAllowance,
            tier = properties.tierForUsage(invitedGuests),
            guestsImported = guestsImported,
            invitationsSentEmail = invitationsSentEmail,
            invitationsSentWhatsapp = invitationsSentWhatsapp,
        )
    }
}
