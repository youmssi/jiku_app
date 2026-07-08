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

        return record.toAllowance()
    }

    @Transactional(readOnly = true)
    fun canInvite(
        eventId: UUID,
        additionalGuests: Long,
    ): Boolean {
        val invited = invitation.guestStats(eventId).invited
        val unlocked = usageRecords.findByEventId(eventId)?.unlockedAllowance ?: properties.freeTierGuests
        return invited + additionalGuests.coerceAtLeast(0) <= unlocked
    }

    private fun UsageRecord.toAllowance(): BillingAllowance {
        val remaining = (unlockedAllowance - invitedGuests).coerceAtLeast(0)
        return BillingAllowance(
            invitedGuests = invitedGuests,
            allowance = unlockedAllowance,
            remaining = remaining,
            // "Within" means usage has not exceeded the ceiling (exactly at it still
            // counts as within); whether more can be sent is a separate check.
            withinAllowance = invitedGuests <= unlockedAllowance,
            tier = properties.tierForUsage(invitedGuests),
            guestsImported = guestsImported,
            invitationsSentEmail = invitationsSentEmail,
            invitationsSentWhatsapp = invitationsSentWhatsapp,
        )
    }
}
