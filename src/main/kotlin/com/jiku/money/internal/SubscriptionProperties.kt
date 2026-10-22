package com.jiku.money.internal

import com.jiku.money.PriceList
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Services plans (JIKU-268): Solo, free for one person, and Services Pro,
 * priced per person who serves clients. A plan's monthly price includes
 * [Plan.includedPeople] people, plus [Plan.extraPerson] for each one beyond.
 * Paying yearly charges [Period.chargedMonths] of the twelve. Everything here
 * is configuration.
 */
@ConfigurationProperties(prefix = "billing.subscription")
data class SubscriptionProperties(
    val plans: List<Plan> =
        listOf(
            Plan(name = "Solo", includedPeople = 1, maxPeople = 1, monthly = PriceList.FREE),
            Plan(
                name = "Services Pro",
                includedPeople = 1,
                monthly = PriceList(60_000, 4_000, 700),
                extraPerson = PriceList(60_000, 4_000, 700),
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
    /** WhatsApp appointment reminders a free plan includes each calendar month (ADR 105). */
    val freePlanWhatsAppReminders: Int = 50,
    /**
     * Fair use of a paid plan (JIKU-268): WhatsApp reminders each person who
     * serves clients may send each calendar month from Jikū's number. Past it,
     * reminders go by the other channels until the next month.
     */
    val paidPlanWhatsAppRemindersPerPerson: Int = 1_000,
    /**
     * Clients one resource may serve in the same slot, by plan name (référentiel
     * métier §3). A plan missing here serves one client at a time.
     */
    val clientsPerSlot: Map<String, Int> = mapOf("Solo" to 1, "Services Pro" to 30),
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
