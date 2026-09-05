package com.jiku.money.internal

import com.jiku.shared.ResourceCountChanged
import com.jiku.shared.TenantContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Le module catalog annonce un changement du nombre de ressources actives
 * (JIKU-90). La première ressource active matérialise un abonnement ; la photo du
 * nombre est rafraîchie ensuite. Rejoint la transaction du catalog — cohérence
 * entre la ressource créée et l'abonnement ouvert.
 */
@Component
class SubscriptionResourceListener(
    private val subscriptionService: SubscriptionService,
) {
    @EventListener
    @Transactional
    fun onResourceCountChanged(event: ResourceCountChanged) {
        val previous = TenantContext.get()
        TenantContext.set(event.tenantId)
        try {
            subscriptionService.applyResourceCount(event.activeResources)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }
}
