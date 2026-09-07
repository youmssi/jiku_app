package com.jiku.money.internal

import com.jiku.shared.TenantContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Balayage des échéances d'abonnement (JIKU-90), plateforme entière : d'abord les
 * abonnements dont la grâce est épuisée (→ EXPIRED + suspension), puis ceux
 * arrivés à échéance (→ GRACE), puis les préavis J-7 restants. Chaque ligne est
 * traitée sous son tenant, comme le balayage des essais (TrialExpiryJob).
 */
@Component
class SubscriptionExpiryJob(
    private val subscriptions: SubscriptionRepository,
    private val properties: SubscriptionProperties,
    private val worker: SubscriptionExpiryWorker,
) {
    @Scheduled(cron = "\${billing.subscription.cron:0 30 4 * * *}")
    fun sweep() {
        val now = Instant.now()
        val graceEnd = now.minus(properties.grace)

        subscriptions.findDueForSuspension(graceEnd).forEach { ref ->
            withTenant(ref.tenantId) { worker.suspend(ref.subscriptionId) }
        }
        subscriptions.findDueForGrace(now).forEach { ref ->
            withTenant(ref.tenantId) { worker.enterGrace(ref.subscriptionId) }
        }
        subscriptions.findDueForExpiryNotice(now, now.plus(properties.noticeLead)).forEach { ref ->
            withTenant(ref.tenantId) { worker.sendExpiryNotice(ref.subscriptionId) }
        }
    }

    private fun <T> withTenant(
        tenantId: String,
        block: () -> T,
    ): T {
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        return try {
            block()
        } finally {
            if (previous == null) {
                TenantContext.clear()
            } else {
                TenantContext.set(previous)
            }
        }
    }
}
