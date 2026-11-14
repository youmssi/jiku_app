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
    /** Paid guests the event is raised to; null for any other purchase. */
    val guests: Long?,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val payee: PayeeDetails,
    /** GUESTS, SUBSCRIPTION, PACK, PACK_EXTRA or COMMISSION (JIKU-307). */
    val kind: String = "GUESTS",
    /** The event a guests or commission payment is for; null for a workspace purchase. */
    val eventId: UUID? = null,
    /** That event's name, when it still exists. */
    val eventName: String? = null,
    /** Months a subscription or Pro Organisateur request pays for. */
    val months: Int? = null,
    val createdAt: Instant? = null,
    /** When the request expires without a confirmed transfer (JIKU-307). */
    val expiresAt: Instant? = null,
    /** When the organizer said the transfer was sent; null until then. */
    val transferDeclaredAt: Instant? = null,
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
    /** Guests the payment buys or settles; null when it buys none. */
    val guests: Long?,
    val amountMinor: Long,
    val currency: String,
    val provider: String,
    val reference: String,
    val status: String,
    val createdAt: Instant,
    /** When the organizer said the transfer was sent (JIKU-307). */
    val transferDeclaredAt: Instant? = null,
    /** The desk's reason when it rejected the request. */
    val rejectionReason: String? = null,
)

/** Réglages de facturation lus par le bureau admin (bénéficiaire, prix par invité, formules). */
data class PlatformBillingSettingsView(
    val currency: String,
    val payee: PayeeDetails,
    /** Tax-inclusive price of one event guest (JIKU-268). */
    val perGuest: PriceList,
    val subscriptionPlans: List<SubscriptionPlanOption>,
    /** Vrai quand la base remplace la configuration d'environnement. */
    val managedInDatabase: Boolean,
)

/** Mise à jour du bureau admin : listes complètes (une soumission remplace tout). */
data class PlatformBillingSettingsUpdate(
    val payee: PayeeDetails,
    val perGuest: PriceList,
    val subscriptionPlans: List<SubscriptionPlanOption>,
)

/** A services plan as the admin desk edits it (ADR 105): prices in GNF, FCFA and USD. */
data class SubscriptionPlanOption(
    val name: String,
    val includedPeople: Long,
    /** Null when the plan has no people cap. */
    val maxPeople: Long?,
    val monthly: PriceList,
    /** Null when the plan takes no one beyond [includedPeople]. */
    val extraPerson: PriceList?,
) {
    /** Kept for /v1 clients written before ADR 105; read [maxPeople] and [includedPeople]. */
    @Deprecated("Use maxPeople and includedPeople")
    val maxResources: Long get() = maxPeople ?: includedPeople

    /** Kept for /v1 clients written before ADR 105: the GNF monthly price. */
    @Deprecated("Use monthly")
    val priceMinorPerMonth: Long get() = monthly.gnf
}
