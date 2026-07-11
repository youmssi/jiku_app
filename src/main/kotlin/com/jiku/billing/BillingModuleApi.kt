package com.jiku.billing

import java.util.UUID

/**
 * The billing module's public API. Other modules read an event's usage allowance
 * through this interface only — never billing's internal tables. Reads are scoped
 * to the current tenant by the persistence-layer tenant filter.
 *
 * Metering (JIKU-32) is deliberately separate from payment (JIKU-33) and paywall
 * enforcement (JIKU-34): this API reports where an event stands; enforcement is
 * layered on top of it.
 */
interface BillingModuleApi {
    /** Current usage and entitlement for [eventId], recomputed from live counts. */
    fun allowance(eventId: UUID): BillingAllowance

    /**
     * Whether sending to [additionalGuests] more distinct guests would stay within
     * the event's unlocked allowance. Used by the invitation module's paywall
     * (JIKU-34); here it simply reflects the metered allowance.
     */
    fun canInvite(
        eventId: UUID,
        additionalGuests: Long,
    ): Boolean

    /**
     * Platform back-office payments desk (JIKU-41). Deliberately cross-tenant:
     * only the admin module may call these, and mutations rebind the payment's
     * own tenant internally before touching tenant-scoped data.
     */
    fun adminListPayments(
        status: String?,
        provider: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): List<AdminPaymentView>

    /** Confirms a manual payment's transfer arrived and unlocks its tier. */
    fun adminConfirmManualPayment(paymentId: UUID): AdminPaymentView

    /** Rejects a pending manual payment with a reason. */
    fun adminRejectManualPayment(
        paymentId: UUID,
        reason: String,
    ): AdminPaymentView
}

/**
 * A snapshot of one event's billing position. [invitedGuests] is the billable
 * usage (distinct guests with at least one sent invitation); [allowance] is the
 * currently unlocked ceiling; [remaining] never goes below zero.
 */
data class BillingAllowance(
    val invitedGuests: Long,
    val allowance: Long,
    val remaining: Long,
    val withinAllowance: Boolean,
    val tier: String,
    val guestsImported: Long,
    val invitationsSentEmail: Long,
    val invitationsSentWhatsapp: Long,
)
