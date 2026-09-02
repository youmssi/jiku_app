package com.jiku.money.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TrialGrantRepository : JpaRepository<TrialGrant, UUID> {
    fun findFirstByEventIdAndStatusOrderByCreatedAtDesc(
        eventId: UUID,
        status: TrialStatus,
    ): TrialGrant?

    /**
     * Cross-tenant sweeps and back-office reads. Native SQL for the same reason as
     * [PaymentRepository.adminList]: the discriminator filter scopes JPQL to the
     * bound tenant and these run with none. Rows are (id, tenant_id) pairs; the
     * caller binds each trial's tenant before touching the entity.
     */
    @Query(
        value = "SELECT id, tenant_id FROM trial_grant WHERE status = 'ACTIVE' AND expires_at <= :now",
        nativeQuery = true,
    )
    fun findDueForExpiry(
        @Param("now") now: Instant,
    ): List<Array<Any>>

    @Query(
        value = """
            SELECT id, tenant_id FROM trial_grant
            WHERE status = 'ACTIVE' AND expiry_notice_sent = false
              AND expires_at > :now AND expires_at <= :noticeCutoff
        """,
        nativeQuery = true,
    )
    fun findDueForExpiryNotice(
        @Param("now") now: Instant,
        @Param("noticeCutoff") noticeCutoff: Instant,
    ): List<Array<Any>>

    @Query(
        value = """
            SELECT * FROM trial_grant
            WHERE (:status IS NULL OR status = :status)
              AND (:tenantId IS NULL OR tenant_id = :tenantId)
            ORDER BY created_at DESC
            LIMIT :limit OFFSET :offset
        """,
        nativeQuery = true,
    )
    fun adminList(
        @Param("status") status: String?,
        @Param("tenantId") tenantId: String?,
        @Param("limit") limit: Int,
        @Param("offset") offset: Int,
    ): List<TrialGrant>

    @Query(value = "SELECT tenant_id FROM trial_grant WHERE id = :id", nativeQuery = true)
    fun findTenantIdById(
        @Param("id") id: UUID,
    ): String?
}
