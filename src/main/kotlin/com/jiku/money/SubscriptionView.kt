package com.jiku.money

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** A tenant's services subscription, for the organizer's billing screen (JIKU-90, ADR 105). */
data class SubscriptionView(
    val plan: String,
    /** Currency every amount below is in (GNF, XOF, XAF or USD, in minor units). */
    val currency: String,
    /** People who serve clients. */
    val resourcesActive: Long,
    /** People the plan's base price covers. */
    val resourcesIncluded: Long,
    /** The team has more people than the plan allows. */
    val overLimit: Boolean,
    val status: String,
    val startedAt: Instant,
    /** End of the paid period; null while a free plan covers the team. */
    val expiresAt: Instant?,
    /** Suspension date (end of grace); null outside grace. */
    val suspensionAt: Instant?,
    /** What the current plan costs per month for this team. */
    val monthlyMinor: Long,
    /** Plans and prepaid lengths on offer, priced for this team. */
    val plans: List<PlanOption>,
    val months: List<MonthOption>,
    /** WhatsApp reminders used this month; null when the plan has no monthly cap (ADR 105). */
    val whatsAppReminders: ReminderAllowance? = null,
)

/** WhatsApp appointment reminders sent this month out of a free plan's allowance (ADR 105). */
data class ReminderAllowance(
    val sent: Int,
    val limit: Int,
)

/** A plan priced in the tenant's billing currency. */
data class PlanOption(
    val name: String,
    val includedPeople: Long,
    /** Null when the plan has no people cap. */
    val maxPeople: Long?,
    /** Base monthly price, covering [includedPeople]. */
    val monthlyMinor: Long,
    /** Monthly price of each person beyond [includedPeople]; null when the plan takes no more. */
    val extraPersonMinor: Long?,
    /** Monthly price for the tenant's current team; null when the plan cannot hold it. */
    val teamMonthlyMinor: Long?,
) {
    /** Kept for /v1 clients written before ADR 105; read [maxPeople] and [includedPeople]. */
    @Deprecated("Use maxPeople and includedPeople")
    val maxResources: Long get() = maxPeople ?: includedPeople

    /** Kept for /v1 clients written before ADR 105; read [monthlyMinor]. */
    @Deprecated("Use monthlyMinor")
    val priceMinorPerMonth: Long get() = monthlyMinor
}

/** A prepaid length and how many of its months are charged (12 for 10 when paying yearly). */
data class MonthOption(
    val months: Int,
    val chargedMonths: Int,
) {
    /** Kept for /v1 clients written before ADR 105: the charged share of the months, per thousand. */
    @Deprecated("Use chargedMonths")
    val factorMilli: Int get() = chargedMonths * 1_000 / months
}

/** Demande de prépaiement : la formule et la durée voulues. */
data class SubscriptionRequest(
    @field:NotBlank val plan: String,
    @field:Min(1) val months: Int,
)
