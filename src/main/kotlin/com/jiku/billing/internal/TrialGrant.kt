package com.jiku.billing.internal

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

/**
 * A time-boxed trial of a paid tier granted by a platform admin (JIKU-42).
 * Deliberately a separate record from [UsageRecord.unlockedAllowance] — that
 * column stays strictly the *paid* entitlement, so expiry can revert a trial
 * without ever guessing whether an allowance was paid for.
 */
@Entity
@Table(name = "trial_grant")
class TrialGrant(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "tier", nullable = false, updatable = false)
    val tier: String,
    /** The allowance the trial unlocks while active (the tier's guest ceiling). */
    @Column(name = "granted_allowance", nullable = false, updatable = false)
    val grantedAllowance: Long,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: TrialStatus = TrialStatus.ACTIVE

    /** Whether the near-expiry notice was already sent (sent at most once). */
    @Column(name = "expiry_notice_sent", nullable = false)
    var expiryNoticeSent: Boolean = false

    /** Why the trial ended early; null unless [status] is ENDED. */
    @Column(name = "ended_reason")
    var endedReason: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /** Whether this trial currently raises the event's allowance. */
    fun isLive(now: Instant): Boolean = status == TrialStatus.ACTIVE && expiresAt.isAfter(now)
}

/**
 * Trial lifecycle: ACTIVE until it either CONVERTED (a confirmed payment landed
 * during the trial), was ENDED early by an admin, or EXPIRED by the sweep job.
 */
enum class TrialStatus {
    ACTIVE,
    CONVERTED,
    ENDED,
    EXPIRED,
}
