package com.jiku.invitation.internal

import com.jiku.shared.TenantContext
import com.jiku.shared.WalkInArrived
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * À chaque sans-rendez-vous inscrit au comptoir côté catalog (JIKU-88),
 * matérialise l'invité et son billet dans le tenant du service. L'événement est
 * traité synchroniquement, dans la transaction de l'inscription.
 */
@Component
class WalkInArrivedListener(
    private val clients: AppointmentClientService,
) {
    @EventListener
    fun on(walkIn: WalkInArrived) {
        val previous = TenantContext.get()
        TenantContext.set(walkIn.tenantId)
        try {
            clients.recordWalkIn(walkIn)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }
}
