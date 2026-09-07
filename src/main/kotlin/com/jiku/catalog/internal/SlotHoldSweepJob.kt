package com.jiku.catalog.internal

import com.jiku.shared.TenantContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Purge planifiée des demandes de rendez-vous en attente arrivées à expiration
 * (JIKU-85/87) : la case redevient réservable. Complète la purge paresseuse du
 * moteur (qui nettoie au moment où quelqu'un réessaie le créneau) et empêche la
 * table des demandes de grossir indéfiniment. Tenant par tenant, comme les autres
 * balayages plateforme : la liste vient d'une requête cross-tenant, chaque
 * suppression s'exécute sous le tenant de ses lignes.
 */
@Component
class SlotHoldSweepJob(
    private val reservations: ServiceReservationRepository,
    private val engine: SlotEngine,
) {
    @Scheduled(cron = "\${appointment.hold.sweep-cron:0 * * * * *}")
    fun sweep() {
        val now = Instant.now()
        for (tenantId in reservations.expiredHoldTenantIds(now)) {
            TenantContext.withTenant(tenantId) { engine.releaseExpiredHolds(now) }
        }
    }
}
