package com.jiku.shared

import java.time.Instant

/**
 * Un changement d'état d'un abonnement (JIKU-90), publié par le module money et
 * consommé par notification. Chaque état écrit à l'organisateur : échéance
 * proche (J-7), entrée en grâce, suspension, réactivation, ou demande de
 * prépaiement émise.
 */
data class SubscriptionNotice(
    val kind: String,
    val tenantId: String,
    val plan: String,
    val months: Int?,
    val amountMinor: Long?,
    val currency: String?,
    val reference: String?,
    val expiresAt: Instant?,
    /** Date de suspension effective (fin de grâce) pour les avis correspondants. */
    val suspensionAt: Instant?,
    val organizerName: String,
    val organizerEmail: String,
    val note: String? = null,
) {
    companion object {
        const val KIND_REQUESTED = "REQUESTED"
        const val KIND_REACTIVATED = "REACTIVATED"
        const val KIND_EXPIRING = "EXPIRING"
        const val KIND_GRACE_STARTED = "GRACE_STARTED"
        const val KIND_EXPIRED = "EXPIRED"
    }
}
