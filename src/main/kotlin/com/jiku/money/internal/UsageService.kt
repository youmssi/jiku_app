package com.jiku.money.internal

import com.jiku.invitation.InvitationModuleApi
import com.jiku.money.BillingAllowance
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Computes and persists per-event usage (JIKU-32). Usage counts are always derived
 * live from the invitation module's authoritative figures — so importing and
 * sending across multiple batches can never double-count or reset — while the
 * paid guests are the stateful entitlement stored on the [UsageRecord] (none
 * while the event runs on the organization's free guests, raised by a purchase).
 *
 * Reading the allowance also refreshes the persisted snapshot, so the record stays
 * an accurate, auditable reflection of current usage without a separate job.
 */
@Service
class UsageService(
    private val usageRecords: UsageRecordRepository,
    private val invitation: InvitationModuleApi,
    private val trialService: TrialService,
    private val tenantQuota: TenantQuotaService,
    private val organizerPack: OrganizerPackService,
    private val eventDay: EventDay,
    private val properties: BillingProperties,
) {
    @Transactional
    fun allowance(eventId: UUID): BillingAllowance {
        val stats = invitation.guestStats(eventId)
        val sent = invitation.sentInvitationCounts(eventId)

        val record =
            usageRecords.findByEventId(eventId) ?: UsageRecord(eventId = eventId)
        record.guestsImported = stats.total
        record.invitedGuests = stats.invited
        record.invitationsSentEmail = sent.email
        record.invitationsSentWhatsapp = sent.whatsapp
        record.updatedAt = Instant.now()
        usageRecords.save(record)

        return buildAllowance(eventId, stats, sent, record.paidGuests)
    }

    /**
     * Même position de facturation que [allowance], **sans** écrire le snapshot :
     * c'est la lecture du tableau de bord organisateur, qui tourne en transaction
     * read-only et que le polling répète — une écriture là-bas serait une
     * amplification d'écriture sur une ligne de facturation à chaque rafraîchissement.
     */
    @Transactional(readOnly = true)
    fun readAllowance(eventId: UUID): BillingAllowance {
        val stats = invitation.guestStats(eventId)
        val sent = invitation.sentInvitationCounts(eventId)
        val paid = usageRecords.findByEventId(eventId)?.paidGuests ?: 0
        return buildAllowance(eventId, stats, sent, paid)
    }

    private fun buildAllowance(
        eventId: UUID,
        stats: com.jiku.invitation.GuestStats,
        sent: com.jiku.invitation.SentInvitationCounts,
        paidGuests: Long,
    ): BillingAllowance {
        val invited = stats.invited
        val effective = effectiveAllowance(eventId, invited, paidGuests, unlimitedOnEventDay = false)
        return BillingAllowance(
            invitedGuests = invited,
            allowance = effective,
            remaining = (effective - invited).coerceAtLeast(0),
            withinAllowance = invited <= effective,
            tier =
                when {
                    organizerPack.isActive() -> BillingProperties.PACK_TIER
                    paidGuests > 0 -> BillingProperties.PAID_TIER
                    else -> BillingProperties.FREE_TIER
                },
            guestsImported = stats.total,
            invitationsSentEmail = sent.email,
            invitationsSentWhatsapp = sent.whatsapp,
            paidGuests = paidGuests,
            freeGuests = if (paidGuests > 0 || organizerPack.isActive()) 0 else tenantQuota.freeCeilingFor(invited),
        )
    }

    @Transactional(readOnly = true)
    fun canInvite(
        eventId: UUID,
        additionalGuests: Long,
    ): Boolean {
        val invited = invitation.guestStats(eventId).invited
        val paid = usageRecords.findByEventId(eventId)?.paidGuests ?: 0
        return invited + additionalGuests.coerceAtLeast(0) <= effectiveAllowance(eventId, invited, paid)
    }

    /**
     * The ceiling actually in force: while still on the free tier, the tenant's
     * cumulative budget (JIKU-54) — computed from [alreadyCommitted], this
     * event's own already-invited guests, so that contribution is never
     * subtracted twice — rather than a per-event default; once the event bought
     * guests, those guests. Either way, raised — never replaced —
     * by a live trial (JIKU-42). Keeping the paid allowance stored and the
     * trial contribution computed means trial expiry needs no write here.
     */
    private fun effectiveAllowance(
        eventId: UUID,
        alreadyCommitted: Long,
        paidGuests: Long,
        unlimitedOnEventDay: Boolean = true,
    ): Long {
        val pack = organizerPack.ceiling(eventId, alreadyCommitted, unlimitedOnEventDay)
        val base =
            when {
                paidGuests > 0 -> maxOf(paidGuests, pack ?: 0)
                pack != null -> pack
                else -> tenantQuota.freeCeilingFor(alreadyCommitted)
            }
        val ceiling = maxOf(base, trialService.liveTrialAllowance(eventId) ?: 0)
        return if (unlimitedOnEventDay && eventDay.isEventDay(eventId)) properties.eventDayGrace.raise(ceiling) else ceiling
    }
}
