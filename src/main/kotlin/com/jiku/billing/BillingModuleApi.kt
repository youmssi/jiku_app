package com.jiku.billing

import java.time.Instant
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

    /** Back-office trial management (JIKU-42); cross-tenant like the payments desk. */
    fun adminListTrials(
        status: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): List<AdminTrialView>

    /** Grants a time-boxed trial of a paid tier to one event. */
    fun adminGrantTrial(
        tenantId: UUID,
        eventId: UUID,
        tier: String,
        expiresAt: Instant,
    ): AdminTrialView

    /** Ends an active trial before its expiry, with a reason. */
    fun adminEndTrial(
        trialId: UUID,
        reason: String,
    ): AdminTrialView

    /** The tier name (JIKU-53 grid) that [guestCount] invited guests falls into. */
    fun tierForGuestCount(guestCount: Long): String

    /**
     * The full price for [tierName] given [guestCount] guests: the fixed tier
     * price, or the CUSTOM formula for usage beyond the last fixed tier. Used by
     * the booking flow (JIKU-55) to quote a reservation before any tenant exists.
     */
    fun priceForTier(
        tierName: String,
        guestCount: Long,
    ): Long

    /** The platform's pricing currency (JIKU-53: GNF). */
    fun currency(): String

    /**
     * The configured fixed-price tiers, ascending by allowance. Exposed so
     * operator-facing surfaces (the admin back-office's trial and agreement
     * forms) offer exactly the tiers the platform is configured with, instead of
     * a hardcoded copy that silently drifts the next time pricing changes.
     */
    fun tierOptions(): List<BillingTierOption>

    /**
     * Unlocks [tierName] for [eventId] — the same effect a payment confirmation
     * has (never lowers an existing allowance). Used when a booking's (JIKU-55)
     * verified deposit already paid for a tier on a freshly provisioned event.
     */
    fun unlockTier(
        eventId: UUID,
        tierName: String,
    )

    /**
     * Records [amountMinor] already paid toward [eventId] outside the normal
     * payment flow (JIKU-57) — a booking deposit or balance — so it is netted
     * off the price the next tier upgrade actually charges, instead of the
     * organizer paying for the same guests twice.
     */
    fun recordPrepayment(
        eventId: UUID,
        amountMinor: Long,
    )
}

/** One configured fixed-price tier: what it costs and how many guests it unlocks. */
data class BillingTierOption(
    val name: String,
    val maxGuests: Long,
    val priceMinor: Long,
)

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
