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
 * Abonnement prépayé par ressource active (JIKU-90). Ops d'état : matérialisation
 * à la première ressource active, vue de l'écran Billing, et réactivation /
 * prolongation quand un paiement de prépaiement est confirmé. Les transitions
 * d'échéance (préavis J-7, grâce, suspension) vivent dans [SubscriptionExpiryJob].
 */
@Service
class SubscriptionService(
    private val subscriptions: SubscriptionRepository,
    private val properties: SubscriptionProperties,
    private val tenantModuleApi: TenantModuleApi,
    private val notifier: SubscriptionNotifier,
) {
    /** La vue d'abonnement du tenant courant, ou null s'il n'en a pas. */
    @Transactional(readOnly = true)
    fun view(): SubscriptionView? {
        val row = current() ?: return null
        val included = row.resourceLimit
        val graceEnd = if (row.status == SubscriptionStatus.GRACE) row.expiresAt.plus(properties.grace) else null
        return SubscriptionView(
            plan = row.plan,
            resourcesActive = row.resourcesActive,
            resourcesIncluded = included,
            overLimit = row.resourcesActive > included,
            status = row.status.name,
            startedAt = row.startedAt,
            expiresAt = row.expiresAt,
            suspensionAt = graceEnd,
            plans = properties.plans.map { PlanOption(it.name, it.maxResources, it.priceMinorPerMonth) },
            months = properties.periods.map { MonthOption(it.months, it.factorMilli) },
        )
    }

    /**
     * Le nombre de ressources actives a changé (module catalog). La première
     * ressource active matérialise un abonnement ACTIVE à la formule couvrante,
     * d'une validité initiale offerte ; ensuite, seule la photo du nombre est
     * rafraîchie — jamais de blocage.
     */
    @Transactional
    fun applyResourceCount(activeResources: Long) {
        val now = Instant.now()
        val row = current()
        if (row == null) {
            if (activeResources <= 0) {
                return
            }
            val plan = properties.planForResources(activeResources)
            subscriptions.save(
                Subscription(
                    plan = plan.name,
                    resourceLimit = plan.maxResources,
                    resourcesActive = activeResources,
                    startedAt = now,
                    expiresAt = now.plus(properties.initialValidity),
                ),
            )
        } else {
            row.resourcesActive = activeResources
            row.updatedAt = now
        }
    }

    /**
     * Un prépaiement vient d'être confirmé : la formule est (ré)activée et
     * prolongée du nombre de mois payés, le tenant est levé s'il était suspendu.
     * La prolongation part de l'échéance en cours si elle est encore future, sinon
     * de maintenant — un renouvellement avant échéance cumule.
     */
    @Transactional
    fun confirmSubscriptionPayment(
        planName: String,
        months: Int,
    ) {
        val plan =
            properties.planByName(planName)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown subscription plan: $planName")
        if (properties.period(months) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported prepaid period: $months months")
        }
        val tenantId = requireNotNull(TenantContext.get()) { "Subscription payment requires an authenticated tenant" }
        val now = Instant.now()
        val row = current() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription for this tenant")

        row.plan = plan.name
        row.resourceLimit = plan.maxResources
        row.status = SubscriptionStatus.ACTIVE
        row.expiresAt = maxOf(row.expiresAt, now).atZone(ZoneOffset.UTC).plusMonths(months.toLong()).toInstant()
        row.expiryNoticeSent = false
        row.graceNoticeSent = false
        row.updatedAt = now

        // Réactivation : lève la suspension si la grâce était épuisée.
        tenantModuleApi.setTenantSuspended(UUID.fromString(tenantId), false)

        notifier.send(
            kind = SubscriptionNotice.KIND_REACTIVATED,
            tenantId = tenantId,
            plan = plan.name,
            months = months,
            expiresAt = row.expiresAt,
        )
    }

    private fun current(): Subscription? = subscriptions.findCurrent().firstOrNull()
}
