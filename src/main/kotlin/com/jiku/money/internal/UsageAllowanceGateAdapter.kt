package com.jiku.money.internal

import com.jiku.shared.UsageAllowanceGate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Billing's implementation of the shared paywall gate (JIKU-34). An event that
 * bought guests reports its own [UsageRecord] ceiling directly; one still on the
 * free guests defers to the tenant's cumulative budget (JIKU-54), never a
 * per-event default — no cross-module read either way, so no dependency cycle
 * with invitation. An active Pro Organisateur (ADR 105) replaces the free budget
 * with its monthly guests. On the event's day the ceiling gets
 * [BillingProperties.EventDayGrace] (JIKU-270), so a last-minute guest still
 * receives an invitation; the extra guests show as owed on the billing screen.
 */
@Component
class UsageAllowanceGateAdapter(
    private val usageRecords: UsageRecordRepository,
    private val tenantQuota: TenantQuotaService,
    private val organizerPack: OrganizerPackService,
    private val eventDay: EventDay,
    private val properties: BillingProperties,
) : UsageAllowanceGate {
    @Transactional(readOnly = true)
    override fun allowanceCeiling(
        eventId: UUID,
        alreadyCommitted: Long,
    ): Long {
        val paid = paidGuests(eventId)
        val packCeiling = organizerPack.ceiling(eventId, alreadyCommitted)
        val ceiling =
            when {
                paid > 0 -> maxOf(paid, packCeiling ?: 0)
                else -> packCeiling ?: tenantQuota.freeCeilingFor(alreadyCommitted)
            }
        return if (eventDay.isEventDay(eventId)) properties.eventDayGrace.raise(ceiling) else ceiling
    }

    override fun recordCommitment(
        eventId: UUID,
        newGuestCount: Long,
    ) {
        if (paidGuests(eventId) > 0) return
        if (organizerPack.recordCommitment(newGuestCount)) return
        tenantQuota.recordFreeCommitment(newGuestCount)
    }

    @Transactional(readOnly = true)
    override fun paidEvent(eventId: UUID): Boolean = organizerPack.isActive() || paidGuests(eventId) > 0

    private fun paidGuests(eventId: UUID): Long = usageRecords.findByEventId(eventId)?.paidGuests ?: 0
}
