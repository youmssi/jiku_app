package com.jiku.money.internal

import com.jiku.money.MonthOption
import com.jiku.money.PlanOption
import com.jiku.money.SubscriptionView
import com.jiku.shared.SubscriptionNotice
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A tenant's services subscription (JIKU-90, priced per team since ADR 105).
 * The first person who serves clients opens it on the free plan; outgrowing a
 * free plan gives the team [SubscriptionProperties.initialValidity] to choose a
 * paid one before the expiry job applies grace and suspension. A confirmed
 * prepayment (re)activates and extends it.
 */
@Service
class SubscriptionService(
    private val subscriptions: SubscriptionRepository,
    private val properties: SubscriptionProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val tenantModuleApi: TenantModuleApi,
    private val notifier: SubscriptionNotifier,
    private val billingCurrency: TenantBillingCurrency,
    private val reminderAllowance: ReminderAllowanceService,
) {
    /** The current tenant's subscription, or null when it has none. */
    @Transactional(readOnly = true)
    fun view(): SubscriptionView? {
        val row = current() ?: return null
        val currency = billingCurrency.current()
        val plan = platformSettings.planByName(row.plan)
        val people = row.resourcesActive
        val graceEnd = if (row.status == SubscriptionStatus.GRACE) row.expiresAt?.plus(properties.grace) else null
        return SubscriptionView(
            plan = row.plan,
            currency = currency,
            resourcesActive = people,
            resourcesIncluded = plan?.includedPeople ?: 0,
            overLimit = plan?.covers(people) == false,
            status = row.status.name,
            startedAt = row.startedAt,
            expiresAt = row.expiresAt,
            suspensionAt = graceEnd,
            monthlyMinor = plan?.monthlyMinor(currency, people) ?: 0,
            plans =
                platformSettings.plans().map {
                    PlanOption(
                        name = it.name,
                        includedPeople = it.includedPeople,
                        maxPeople = it.maxPeople,
                        monthlyMinor = it.monthly.amountMinor(currency),
                        extraPersonMinor = it.extraPerson?.amountMinor(currency),
                        teamMonthlyMinor = if (it.covers(people)) it.monthlyMinor(currency, people) else null,
                    )
                },
            months = properties.periods.map { MonthOption(it.months, it.chargedMonths) },
            whatsAppReminders = reminderAllowance.view(),
        )
    }

    /**
     * The number of people who serve clients changed (catalog module). The first
     * one opens a subscription on the smallest plan that holds the team, free
     * when it can be. On a free plan, outgrowing it starts the window to choose a
     * paid plan, and shrinking back ends it. Never blocks.
     */
    @Transactional
    fun applyResourceCount(activePeople: Long) {
        val now = Instant.now()
        val row = current()
        if (row == null) {
            if (activePeople <= 0) return
            val plan = platformSettings.openingPlan(activePeople)
            subscriptions.save(
                Subscription(
                    plan = plan.name,
                    resourceLimit = plan.maxPeople,
                    resourcesActive = activePeople,
                    startedAt = now,
                    expiresAt = if (plan.free) null else now.plus(properties.initialValidity),
                ),
            )
            return
        }
        row.resourcesActive = activePeople
        row.updatedAt = now
        val plan = platformSettings.planByName(row.plan) ?: return
        if (!plan.free) return
        if (!plan.covers(activePeople)) {
            if (row.expiresAt == null) row.expiresAt = now.plus(properties.initialValidity)
        } else if (row.status == SubscriptionStatus.ACTIVE) {
            row.expiresAt = null
            row.expiryNoticeSent = false
        }
    }

    /**
     * A prepayment was confirmed: the plan is (re)activated and extended by the
     * months paid, and the tenant is lifted if it was suspended. The extension
     * starts from the current end when it is still ahead, so renewing early adds up.
     */
    @Transactional
    fun confirmSubscriptionPayment(
        planName: String,
        months: Int,
    ) {
        val plan =
            platformSettings.planByName(planName)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown subscription plan: $planName")
        if (properties.period(months) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported prepaid period: $months months")
        }
        val tenantId = requireNotNull(TenantContext.get()) { "Subscription payment requires an authenticated tenant" }
        val now = Instant.now()
        val row = current() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription for this tenant")
        val from = row.expiresAt?.takeIf { it.isAfter(now) } ?: now

        row.plan = plan.name
        row.resourceLimit = plan.maxPeople
        row.status = SubscriptionStatus.ACTIVE
        row.expiresAt = from.atZone(ZoneOffset.UTC).plusMonths(months.toLong()).toInstant()
        row.expiryNoticeSent = false
        row.graceNoticeSent = false
        row.updatedAt = now

        tenantModuleApi.setTenantSuspended(UUID.fromString(tenantId), false)

        notifier.send(
            kind = SubscriptionNotice.KIND_REACTIVATED,
            tenantId = tenantId,
            plan = plan.name,
            months = months,
            expiresAt = row.expiresAt,
        )
    }

    /** Price of [months] of [plan] for the current team, in the tenant's billing currency. */
    @Transactional(readOnly = true)
    fun quote(
        plan: Plan,
        months: Int,
    ): SubscriptionQuote {
        val period =
            properties.period(months)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported prepaid period: $months months")
        if (plan.free) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "${plan.name} is free")
        val people = current()?.resourcesActive ?: plan.includedPeople
        if (!plan.covers(people)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "${plan.name} allows at most ${plan.maxPeople} people; the team has $people")
        }
        val currency = billingCurrency.current()
        return SubscriptionQuote(amountMinor = plan.monthlyMinor(currency, people) * period.chargedMonths, currency = currency)
    }

    private fun current(): Subscription? = subscriptions.findCurrent().firstOrNull()
}

data class SubscriptionQuote(
    val amountMinor: Long,
    val currency: String,
)
