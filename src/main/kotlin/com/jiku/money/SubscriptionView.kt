package com.jiku.money

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Instant

/** Vue d'un abonnement pour l'écran Billing de l'organisateur (JIKU-90). */
data class SubscriptionView(
    val plan: String,
    val resourcesActive: Long,
    val resourcesIncluded: Long,
    /** Le tenant a plus de ressources actives que la formule n'en inclut. */
    val overLimit: Boolean,
    val status: String,
    val startedAt: Instant,
    val expiresAt: Instant,
    /** Date de suspension (fin de grâce) ; nulle hors période de grâce. */
    val suspensionAt: Instant?,
    /** Formules et durées offertes, pour bâtir la demande de prépaiement. */
    val plans: List<PlanOption>,
    val months: List<MonthOption>,
)

/** Une formule affichée à la souscription : plafond et prix mensuel. */
data class PlanOption(
    val name: String,
    val maxResources: Long,
    val priceMinorPerMonth: Long,
)

/** Une durée de prépaiement offerte et son facteur de remise (pour mille). */
data class MonthOption(
    val months: Int,
    val factorMilli: Int,
)

/** Demande de prépaiement : la formule et la durée voulues. */
data class SubscriptionRequest(
    @field:NotBlank val plan: String,
    @field:Min(1) val months: Int,
)
