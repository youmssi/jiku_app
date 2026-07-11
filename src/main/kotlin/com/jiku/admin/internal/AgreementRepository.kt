package com.jiku.admin.internal

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AgreementRepository : JpaRepository<Agreement, UUID> {
    @Query(
        """
        SELECT a FROM Agreement a
        WHERE (:status IS NULL OR a.status = :status)
          AND (:kind IS NULL OR a.kind = :kind)
          AND (:tenantId IS NULL OR a.tenantId = :tenantId)
        ORDER BY a.createdAt DESC
        """,
    )
    fun search(
        @Param("status") status: AgreementStatus?,
        @Param("kind") kind: AgreementKind?,
        @Param("tenantId") tenantId: UUID?,
        pageable: Pageable,
    ): Page<Agreement>

    fun findByStatusAndPeriodEndBefore(
        status: AgreementStatus,
        cutoff: Instant,
    ): List<Agreement>

    fun existsByTenantIdAndKindAndStatus(
        tenantId: UUID,
        kind: AgreementKind,
        status: AgreementStatus,
    ): Boolean
}
