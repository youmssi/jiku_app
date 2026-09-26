package com.jiku.money.internal

import com.jiku.shared.ResourceCountChanged
import com.jiku.shared.TenantTransaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Le module catalog annonce un changement du nombre de ressources actives
 * (JIKU-90). La première ressource active matérialise un abonnement ; la photo du
 * nombre est rafraîchie ensuite. Rejoint la transaction du catalog — cohérence
 * entre la ressource créée et l'abonnement ouvert.
 */
@Component
class SubscriptionResourceListener(
    private val tenantTransaction: TenantTransaction,
    private val subscriptionService: SubscriptionService,
) {
    @EventListener
    fun onResourceCountChanged(event: ResourceCountChanged) {
        tenantTransaction.run(event.tenantId) {
            subscriptionService.applyResourceCount(event.activeResources)
        }
    }
}
