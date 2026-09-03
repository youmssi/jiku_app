package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * The cumulative free-tier budget for one tenant account (JIKU-54): one row per
 * tenant, never per event. [freeInvitesUsed] is the running total across every
 * one of the tenant's events and only ever grows within the rolling window
 * ([TenantQuotaService] rolls it forward) — the anti-fragmentation counter a
 * per-event allowance could not provide, since ten events of ten guests would
 * otherwise never trip a ceiling.
 */
@Entity
@Table(
    name = "tenant_quota",
    uniqueConstraints = [UniqueConstraint(name = "uq_tenant_quota_tenant", columnNames = ["tenant_id"])],
)
class TenantQuota(
    @Column(name = "free_invites_limit", nullable = false)
    var freeInvitesLimit: Long,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "free_invites_used", nullable = false)
    var freeInvitesUsed: Long = 0

    @Column(name = "window_start", nullable = false)
    var windowStart: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
