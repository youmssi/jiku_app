package com.jiku.money.internal

import com.jiku.money.PriceList
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Services plans (ADR 105, decision 2). A plan is priced for the team: a
 * monthly price that includes [Plan.includedPeople] people who serve clients,
 * plus [Plan.extraPerson] for each one beyond. Paying yearly charges
 * [Period.chargedMonths] of the twelve. Everything here is configuration.
 */
@ConfigurationProperties(prefix = "billing.subscription")
data class SubscriptionProperties(
    val plans: List<Plan> =
        listOf(
            Plan(name = "Solo", includedPeople = 1, maxPeople = 1, monthly = PriceList.FREE),
            Plan(name = "Solo Plus", includedPeople = 1, maxPeople = 1, monthly = PriceList(50_000, 3_500, 600)),
            Plan(
                name = "Teams",
                includedPeople = 2,
                monthly = PriceList(150_000, 10_000, 1_700),
                extraPerson = PriceList(50_000, 3_500, 600),
            ),
            Plan(
                name = "Organisation",
                includedPeople = 5,
                monthly = PriceList(350_000, 25_000, 4_000),
                extraPerson = PriceList(40_000, 2_500, 500),
            ),
        ),
    val periods: List<Period> =
        listOf(
            Period(months = 1, chargedMonths = 1),
            Period(months = 12, chargedMonths = 10),
        ),
    /** Time a team gets to choose a paid plan once it outgrows a free one. */
    val initialValidity: Duration = Duration.ofDays(14),
    /** Notice before a paid period ends. */
    val noticeLead: Duration = Duration.ofDays(7),
    /** Tolerance after the end of a paid period before suspension. */
    val grace: Duration = Duration.ofDays(3),
) {
    fun period(months: Int): Period? = periods.firstOrNull { it.months == months }
}

data class Plan(
    val name: String,
    /** People who serve clients covered by [monthly]. */
    val includedPeople: Long,
    /** Most people the plan allows; null when there is no cap. */
    val maxPeople: Long? = null,
    val monthly: PriceList,
    /** Price of each person beyond [includedPeople]; null when the plan has no room for more. */
    val extraPerson: PriceList? = null,
) {
    val free: Boolean get() = monthly.isFree()

    fun covers(people: Long): Boolean = maxPeople == null || people <= maxPeople

    /** Monthly price for a team of [people] in [billingCurrency]. */
    fun monthlyMinor(
        billingCurrency: String,
        people: Long,
    ): Long {
        val extra = (people - includedPeople).coerceAtLeast(0)
        return monthly.amountMinor(billingCurrency) + extra * (extraPerson?.amountMinor(billingCurrency) ?: 0)
    }
}

/** A prepaid length and the number of months it charges. */
data class Period(
    val months: Int,
    val chargedMonths: Int,
)
