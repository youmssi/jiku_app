package com.jiku.money.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SubscriptionRepository : JpaRepository<Subscription, UUID> {
    /**
     * L'abonnement du tenant courant (une ligne par tenant) ; HQL pour que le
     * filtre de tenant s'applique. Vide si le tenant n'en a pas encore.
     */
    @Query("SELECT s FROM Subscription s")
    fun findCurrent(): List<Subscription>

    /** Abonnements ACTIVE dont l'échéance approche sans avis envoyé (tous tenants). */
    @Query(
        value =
            "SELECT id AS subscriptionId, tenant_id AS tenantId FROM subscription " +
                "WHERE status = 'ACTIVE' AND expiry_notice_sent = FALSE AND expires_at > :now AND expires_at <= :cutoff",
        nativeQuery = true,
    )
    fun findDueForExpiryNotice(
        @Param("now") now: Instant,
        @Param("cutoff") cutoff: Instant,
    ): List<SubscriptionSweepRef>

    /** Abonnements ACTIVE arrivés à échéance (à passer en grâce). */
    @Query(
        value =
            "SELECT id AS subscriptionId, tenant_id AS tenantId FROM subscription " +
                "WHERE status = 'ACTIVE' AND expires_at <= :now",
        nativeQuery = true,
    )
    fun findDueForGrace(
        @Param("now") now: Instant,
    ): List<SubscriptionSweepRef>

    /** Abonnements GRACE dont la grâce est achevée (à expirer et suspendre). */
    @Query(
        value =
            "SELECT id AS subscriptionId, tenant_id AS tenantId FROM subscription " +
                "WHERE status = 'GRACE' AND expires_at <= :cutoff",
        nativeQuery = true,
    )
    fun findDueForSuspension(
        @Param("cutoff") cutoff: Instant,
    ): List<SubscriptionSweepRef>
}

interface SubscriptionSweepRef {
    val subscriptionId: UUID
    val tenantId: String
}
