package com.jiku.money

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
     * La position de facturation de [eventId] **sans** rafraîchir le snapshot
     * persisté : la lecture du tableau de bord (polling, transaction read-only)
     * n'écrit jamais dans une ligne de facturation.
     */
    fun readAllowance(eventId: UUID): BillingAllowance

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
    ): AdminTrialPage

    /** Platform-wide trial funnel snapshot (JIKU-99) for the back-office overview strip. */
    fun adminTrialStats(): AdminTrialStats

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
     * Réglages de facturation complets (bénéficiaire + grilles de prix) tels que
     * le bureau admin les voit et les modifie. Lecture et écriture sont ici,
     * derrière l'API du module — le back-office ne touche jamais aux tables.
     */
    fun adminBillingSettings(): PlatformBillingSettingsView

    fun adminUpdateBillingSettings(
        update: PlatformBillingSettingsUpdate,
        updatedBy: String?,
    ): PlatformBillingSettingsView
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
