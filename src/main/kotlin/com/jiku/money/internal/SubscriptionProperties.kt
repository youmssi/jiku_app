package com.jiku.money.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Formules et prix de l'abonnement prépayé par ressource active (JIKU-90).
 * Aucun littéral métier dans le code : les formules, leurs seuils, les périodes
 * de prépaiement et les fenêtres (préavis, grâce, validité initiale) sont de la
 * configuration. Une formule couvre jusqu'à [Plan.maxResources] ressources
 * actives ; au-delà, le tenant est signalé en dépassement (jamais bloqué
 * brutalement — la sanction est la fin de la grâce, puis la suspension).
 */
@ConfigurationProperties(prefix = "billing.subscription")
data class SubscriptionProperties(
    val plans: List<Plan> =
        listOf(
            Plan(name = "Solo", maxResources = 1, priceMinorPerMonth = 100_000),
            Plan(name = "Équipe", maxResources = 5, priceMinorPerMonth = 250_000),
            Plan(name = "Salon", maxResources = 15, priceMinorPerMonth = 500_000),
        ),
    val periods: List<Period> =
        listOf(
            Period(months = 1, factorMilli = 1_000),
            Period(months = 3, factorMilli = 950),
            Period(months = 6, factorMilli = 900),
            Period(months = 12, factorMilli = 850),
        ),
    /** Validité offerte quand la première ressource active ouvre un abonnement. */
    val initialValidity: java.time.Duration = java.time.Duration.ofDays(14),
    /** Préavis d'échéance (J-7 par défaut). */
    val noticeLead: java.time.Duration = java.time.Duration.ofDays(7),
    /** Tolérance après l'échéance avant la suspension. */
    val grace: java.time.Duration = java.time.Duration.ofDays(3),
) {
    /** Une formule, résolue par son nom (insensible à la casse). */
    fun planByName(name: String): Plan? = plans.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Formule couvrant un nombre de ressources actives ; la plus haute sinon. */
    fun planForResources(activeResources: Long): Plan =
        plans.sortedBy { it.maxResources }.firstOrNull { activeResources <= it.maxResources } ?: plans.maxByOrNull { it.maxResources }!!

    /** Multiplicateur de prépaiement pour une durée donnée, s'il est offert. */
    fun period(months: Int): Period? = periods.firstOrNull { it.months == months }

    /** Prix du prépaiement (en minor unit) pour une formule et une durée offertes. */
    fun priceMinor(
        plan: Plan,
        months: Int,
    ): Long? =
        period(months)?.let { p ->
            plan.priceMinorPerMonth * months * p.factorMilli / 1_000
        }

    /** Durées offertes, triées. */
    fun offeredMonths(): List<Int> = periods.map { it.months }.sorted()
}

/** Une formule : son nom d'affichage, son plafond de ressources, son prix mensuel. */
data class Plan(
    val name: String,
    val maxResources: Long,
    val priceMinorPerMonth: Long,
)

/** Une durée de prépaiement et son facteur de remise (pour mille). */
data class Period(
    val months: Int,
    val factorMilli: Int,
)
