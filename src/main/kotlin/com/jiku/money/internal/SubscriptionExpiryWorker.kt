package com.jiku.money.internal

import com.jiku.shared.SubscriptionNotice
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Transitions d'un abonnement à son échéance (JIKU-90), chacune dans sa propre
 * transaction et sous le tenant de la ligne (le balayage lie le contexte avant
 * d'appeler). Les gardes sur le statut rendent chaque transition idempotente :
 * deux instances du job ne notifient ni ne suspendent deux fois.
 */
@Component
class SubscriptionExpiryWorker(
    private val subscriptions: SubscriptionRepository,
    private val properties: SubscriptionProperties,
    private val tenantModuleApi: TenantModuleApi,
    private val notifier: SubscriptionNotifier,
) {
    /** Préavis J-7 : une fois, avant l'échéance. */
    @Transactional
    fun sendExpiryNotice(subscriptionId: UUID) {
        val row = subscriptions.findById(subscriptionId).orElse(null) ?: return
        if (row.status != SubscriptionStatus.ACTIVE || row.expiryNoticeSent) {
            return
        }
        row.expiryNoticeSent = true
        row.updatedAt = Instant.now()
        notifier.send(
            kind = SubscriptionNotice.KIND_EXPIRING,
            tenantId = requireNotNull(row.tenantId),
            plan = row.plan,
            expiresAt = row.expiresAt,
        )
    }

    /** Échéance atteinte sans renouvellement : entrée en période de grâce. */
    @Transactional
    fun enterGrace(subscriptionId: UUID) {
        val row = subscriptions.findById(subscriptionId).orElse(null) ?: return
        if (row.status != SubscriptionStatus.ACTIVE) {
            return
        }
        val expiresAt = row.expiresAt ?: return
        row.status = SubscriptionStatus.GRACE
        row.updatedAt = Instant.now()
        notifier.send(
            kind = SubscriptionNotice.KIND_GRACE_STARTED,
            tenantId = requireNotNull(row.tenantId),
            plan = row.plan,
            expiresAt = expiresAt,
            suspensionAt = expiresAt.plus(properties.grace),
        )
    }

    /** Fin de grâce : expiration définitive et suspension par le kill-switch. */
    @Transactional
    fun suspend(subscriptionId: UUID) {
        val row = subscriptions.findById(subscriptionId).orElse(null) ?: return
        if (row.status != SubscriptionStatus.GRACE) {
            return
        }
        val tenantId = requireNotNull(row.tenantId)
        val expiresAt = row.expiresAt ?: return
        val suspensionAt = expiresAt.plus(properties.grace)
        row.status = SubscriptionStatus.EXPIRED
        row.updatedAt = Instant.now()
        tenantModuleApi.setTenantSuspended(UUID.fromString(tenantId), true)
        notifier.send(
            kind = SubscriptionNotice.KIND_EXPIRED,
            tenantId = tenantId,
            plan = row.plan,
            expiresAt = expiresAt,
            suspensionAt = suspensionAt,
        )
    }
}
