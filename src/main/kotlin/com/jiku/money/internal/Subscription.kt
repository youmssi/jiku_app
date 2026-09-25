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
 * A tenant's services subscription (JIKU-90, priced per team since ADR 105), one
 * row per tenant. The plan and its cap are a snapshot of the last commitment;
 * resources_active counts the people who serve clients, kept current by the
 * catalog module's ResourceCountChanged events.
 */
@Entity
@Table(name = "subscription")
class Subscription(
    @Column(name = "plan", nullable = false, length = 32)
    var plan: String,
    /** Most people the plan allows; null when it has no cap. */
    @Column(name = "resource_limit")
    var resourceLimit: Long?,
    @Column(name = "resources_active", nullable = false)
    var resourcesActive: Long,
    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant,
    /** End of the paid period; null while a free plan covers the team. */
    @Column(name = "expires_at")
    var expiresAt: Instant?,
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
