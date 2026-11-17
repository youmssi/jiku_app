package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** What a gift gives (JIKU-315). */
enum class GiftKind {
    /** Guests lent to one event for a number of days, as a trial is. */
    EVENT_GUESTS,

    /** Months of a services plan. */
    SERVICES_PLAN,

    /** Months of the organizer pack. */
    ORGANIZER_PACK,
}

/** A gift waits to be activated, then is used up; unclaimed it expires, and the desk may take it back before. */
enum class GiftStatus {
    OFFERED,
    ACTIVATED,
    EXPIRED,
    REVOKED,
}

/**
 * Something the back office offers an organization (JIKU-315). It waits until
 * the organizer activates it, which applies it the way a confirmed payment
 * would, with no payment and no invoice. Guests go through a trial grant, so
 * they keep the trial's life: live until their end, kept if bought meanwhile.
 */
@Entity
@Table(name = "gift")
class Gift(
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 32)
    val kind: GiftKind,
    @Column(name = "guests", updatable = false)
    val guests: Long?,
    @Column(name = "days", updatable = false)
    val days: Int?,
    @Column(name = "plan", updatable = false, length = 32)
    val plan: String?,
    @Column(name = "months", updatable = false)
    val months: Int?,
    /** The event an EVENT_GUESTS gift is for; null lets the organizer choose. */
    @Column(name = "event_id", updatable = false)
    val eventId: UUID?,
    @Column(name = "message", updatable = false, length = 500)
    val message: String?,
    @Column(name = "claim_by", nullable = false, updatable = false)
    val claimBy: Instant,
    @Column(name = "created_by", updatable = false)
    val createdBy: String?,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: GiftStatus = GiftStatus.OFFERED

    @Column(name = "activated_at")
    var activatedAt: Instant? = null

    @Column(name = "activated_event_id")
    var activatedEventId: UUID? = null

    @Column(name = "trial_grant_id")
    var trialGrantId: UUID? = null

    @Column(name = "revoked_reason", length = 500)
    var revokedReason: String? = null

    @Column(name = "claim_notice_sent", nullable = false)
    var claimNoticeSent: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

interface GiftRepository : JpaRepository<Gift, UUID> {
    fun findAllByOrderByCreatedAtDesc(): List<Gift>

    /** The row locked for activation, so two clicks cannot both use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Gift g where g.id = :id")
    fun findForUpdate(
        @Param("id") id: UUID,
    ): Optional<Gift>

    /** Offered gifts past their claim date, across tenants, for the sweep. */
    @Query(
        value = "SELECT id, tenant_id FROM gift WHERE status = 'OFFERED' AND claim_by < :now ORDER BY claim_by LIMIT 500",
        nativeQuery = true,
    )
    fun findUnclaimedPast(
        @Param("now") now: Instant,
    ): List<Array<Any>>

    /** Offered gifts whose claim date is near and not yet announced, across tenants. */
    @Query(
        value =
            "SELECT id, tenant_id FROM gift WHERE status = 'OFFERED' AND claim_notice_sent = FALSE " +
                "AND claim_by >= :now AND claim_by < :before ORDER BY claim_by LIMIT 500",
        nativeQuery = true,
    )
    fun findClaimEndingBefore(
        @Param("now") now: Instant,
        @Param("before") before: Instant,
    ): List<Array<Any>>

    @Query(value = "SELECT tenant_id FROM gift WHERE id = :id", nativeQuery = true)
    fun findTenantIdById(
        @Param("id") id: UUID,
    ): String?

    @Query(
        value =
            "SELECT * FROM gift WHERE (:status IS NULL OR status = :status) " +
                "AND (:tenantId IS NULL OR tenant_id = :tenantId) " +
                "ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun adminList(
        @Param("status") status: String?,
        @Param("tenantId") tenantId: String?,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<Gift>

    @Query(
        value =
            "SELECT COUNT(*) FROM gift WHERE (:status IS NULL OR status = :status) " +
                "AND (:tenantId IS NULL OR tenant_id = :tenantId)",
        nativeQuery = true,
    )
    fun countAdminList(
        @Param("status") status: String?,
        @Param("tenantId") tenantId: String?,
    ): Long
}
