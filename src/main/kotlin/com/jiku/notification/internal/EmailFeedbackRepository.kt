package com.jiku.notification.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/**
 * Reads are intentionally global (not tenant-filtered) — [EmailFeedback] is not a
 * tenant-scoped entity, so these JPQL queries see every tenant's feedback, as the
 * platform reputation job and the cross-tenant undeliverable check require.
 */
interface EmailFeedbackRepository : JpaRepository<EmailFeedback, UUID> {
    fun countByRecipientAndFeedbackType(
        recipient: String,
        feedbackType: String,
    ): Long

    @Query(
        "SELECT COUNT(f) FROM EmailFeedback f WHERE f.feedbackType IN :types AND f.createdAt >= :since",
    )
    fun countByTypesSince(
        @Param("types") types: Collection<String>,
        @Param("since") since: Instant,
    ): Long

    @Query(
        "SELECT COUNT(f) FROM EmailFeedback f " +
            "WHERE f.tenantId = :tenantId AND f.feedbackType IN :types AND f.createdAt >= :since",
    )
    fun countByTenantAndTypesSince(
        @Param("tenantId") tenantId: String,
        @Param("types") types: Collection<String>,
        @Param("since") since: Instant,
    ): Long
}
