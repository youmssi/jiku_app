package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Vie d'un abonnement prépayé (JIKU-90). */
enum class SubscriptionStatus {
    /** Période payée (ou validité initiale offerte) en cours. */
    ACTIVE,

    /** Échéance dépassée ; tolérance en cours avant suspension. */
    GRACE,

    /** Fin de grâce atteinte ; le tenant a été suspendu par le kill-switch. */
    EXPIRED,
}

/**
 * Abonnement prépayé par ressource active d'un tenant (JIKU-90), une ligne par
 * tenant. La formule et son plafond sont un instantané du dernier engagement ;
 * resources_active est la photo du nombre de ressources actives, tenue à jour par
 * les événements ResourceCountChanged du module catalog.
 */
@Entity
@Table(name = "subscription")
class Subscription(
    @Column(name = "plan", nullable = false, length = 32)
    var plan: String,
    @Column(name = "resource_limit", nullable = false)
    var resourceLimit: Long,
    @Column(name = "resources_active", nullable = false)
    var resourcesActive: Long,
    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: SubscriptionStatus = SubscriptionStatus.ACTIVE

    @Column(name = "expiry_notice_sent", nullable = false)
    var expiryNoticeSent: Boolean = false

    @Column(name = "grace_notice_sent", nullable = false)
    var graceNoticeSent: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
