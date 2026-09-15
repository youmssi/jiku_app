package com.jiku.money

import java.time.Instant
import java.util.UUID

/**
 * Payment instructions for a manual (concierge) activation request (JIKU-41):
 * what to pay, where to send it, and the reference to quote. Details flow one
 * way — platform to client; no client payment credential is ever collected.
 */
data class ManualPaymentInstructions(
    val paymentId: UUID,
    val reference: String,
    val tier: String,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val payee: PayeeDetails,
)

data class PayeeDetails(
    val payeeName: String?,
    val contactEmail: String?,
    val contactPhone: String?,
    val mobileMoneyNumber: String?,
    val mobileMoneyOperator: String?,
    val bankDetails: String?,
)

/** One payment row as the platform back-office sees it (cross-tenant). */
data class AdminPaymentView(
    val id: UUID,
    val tenantId: String,
    /** Nul pour un renouvellement d'abonnement (JIKU-90). */
    val eventId: UUID?,
    val tier: String,
    val amountMinor: Long,
    val currency: String,
    val provider: String,
    val reference: String,
    val status: String,
    val createdAt: Instant,
)

/** Réglages de facturation lus par le bureau admin (bénéficiaire + grilles de prix). */
data class PlatformBillingSettingsView(
    val currency: String,
    val payee: PayeeDetails,
    val tiers: List<BillingTierOption>,
    val subscriptionPlans: List<SubscriptionPlanOption>,
    /** Vrai quand la base remplace la configuration d'environnement. */
    val managedInDatabase: Boolean,
)

/** Mise à jour du bureau admin : listes complètes (une soumission remplace tout). */
data class PlatformBillingSettingsUpdate(
    val payee: PayeeDetails,
    val tiers: List<BillingTierOption>,
    val subscriptionPlans: List<SubscriptionPlanOption>,
)

/** Une formule d'abonnement telle que le bureau admin la voit. */
data class SubscriptionPlanOption(
    val name: String,
    val maxResources: Long,
    val priceMinorPerMonth: Long,
)
