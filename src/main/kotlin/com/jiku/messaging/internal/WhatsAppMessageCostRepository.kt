package com.jiku.messaging.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface WhatsAppMessageCostRepository : JpaRepository<WhatsAppMessageCost, UUID> {
    /** Tenant-scoped: a BYO tenant's own conversation window (the Hibernate tenant filter applies). */
    fun countByPoolAndCreatedAtAfter(
        pool: String,
        since: Instant,
    ): Long

    /**
     * The shared platform number's conversation window spans every tenant sending
     * through it — native SQL so the tenant filter does not apply.
     */
    @Query(
        value = "SELECT COUNT(*) FROM whatsapp_message_cost WHERE pool = 'PLATFORM' AND created_at >= :since",
        nativeQuery = true,
    )
    fun countPlatformSince(
        @Param("since") since: Instant,
    ): Long

    @Query("SELECT COALESCE(SUM(w.costUsdMinor), 0) FROM WhatsAppMessageCost w WHERE w.eventId = :eventId")
    fun sumUsdMinorByEventId(
        @Param("eventId") eventId: UUID,
    ): Long

    @Query("SELECT COALESCE(SUM(w.costGnfMinor), 0) FROM WhatsAppMessageCost w WHERE w.eventId = :eventId")
    fun sumGnfMinorByEventId(
        @Param("eventId") eventId: UUID,
    ): Long

    fun countByEventId(eventId: UUID): Long
}
