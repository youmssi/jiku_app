package com.jiku.money.internal

import com.jiku.shared.SubscriptionNotice
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
    private val notifier: SubscriptionNotifier,
    private val platformSettings: PlatformBillingSettingsService,
) {
    /**
     * A free plan that holds the team is set back to running with no end date
     * instead of moving towards a pause (JIKU-303). True when it was.
     */
    private fun keptFree(row: Subscription): Boolean {
        if (!platformSettings.keepsTeamFree(row.plan, row.resourcesActive)) return false
        row.status = SubscriptionStatus.ACTIVE
        row.expiresAt = null
        row.expiryNoticeSent = false
        row.graceNoticeSent = false
        row.updatedAt = Instant.now()
        return true
    }

    /** Préavis J-7 : une fois, avant l'échéance. */
    @Transactional
    fun sendExpiryNotice(subscriptionId: UUID) {
        val row = subscriptions.findById(subscriptionId).orElse(null) ?: return
        if (keptFree(row)) return
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
        if (keptFree(row)) return
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

    /**
     * Fin de grâce : l'abonnement expire et les services se mettent en pause
     * (JIKU-254) — plus de nouvelles réservations ni de tickets de file, mais
     * l'organisation, ses événements et l'accès du propriétaire, qui peut
     * renouveler, restent intacts. La suspension complète reste une décision
     * de la plateforme, jamais une conséquence de facturation.
     */
    @Transactional
    fun pauseServices(subscriptionId: UUID) {
        val row = subscriptions.findById(subscriptionId).orElse(null) ?: return
        if (keptFree(row)) return
        if (row.status != SubscriptionStatus.GRACE) {
            return
        }
        val tenantId = requireNotNull(row.tenantId)
        val expiresAt = row.expiresAt ?: return
        val suspensionAt = expiresAt.plus(properties.grace)
        row.status = SubscriptionStatus.EXPIRED
        row.updatedAt = Instant.now()
        notifier.send(
            kind = SubscriptionNotice.KIND_EXPIRED,
            tenantId = tenantId,
            plan = row.plan,
            expiresAt = expiresAt,
            suspensionAt = suspensionAt,
        )
    }
}
