package com.jiku.notification.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface NotificationLogRepository : JpaRepository<NotificationLog, UUID> {
    fun findByReferenceId(referenceId: UUID): List<NotificationLog>

    fun countByStatus(status: String): Long

    /** Tenant-scoped: successful sends for the current tenant since [since]. */
    fun countByStatusAndCreatedAtAfter(
        status: String,
        since: Instant,
    ): Long

    /**
     * Global count of successful sends since [since], across every tenant — native
     * SQL so the tenant filter does not apply (platform-wide reputation needs all
     * sends).
     */
    @Query(
        value = "SELECT COUNT(*) FROM notification_log WHERE status = 'SENT' AND created_at >= :since",
        nativeQuery = true,
    )
    fun countSentSinceGlobal(
        @Param("since") since: Instant,
    ): Long

    /** The tenant of the most recent successful send to [recipient], across tenants. */
    @Query(
        value =
            "SELECT tenant_id FROM notification_log WHERE recipient = :recipient AND status = 'SENT' " +
                "ORDER BY created_at DESC LIMIT 1",
        nativeQuery = true,
    )
    fun findTenantOfLatestSend(
        @Param("recipient") recipient: String,
    ): String?
}
