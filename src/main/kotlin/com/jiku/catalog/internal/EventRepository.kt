package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface EventRepository : JpaRepository<Event, UUID> {
    /**
     * Cross-tenant list of events whose date is before [cutoff], for the retention
     * job. Native SQL deliberately bypasses the Hibernate tenant filter — this is a
     * platform-wide sweep — returning each event's id and owning tenant.
     */
    @Query(
        nativeQuery = true,
        value =
            "SELECT CAST(id AS VARCHAR) AS id, tenant_id FROM event " +
                "WHERE COALESCE(end_date_time, start_date_time) IS NOT NULL " +
                "AND COALESCE(end_date_time, start_date_time) < :cutoff",
    )
    fun findEventsPastRetention(
        @Param("cutoff") cutoff: Instant,
    ): List<Array<Any>>

    /**
     * Atomically takes one attendance slot if capacity allows. Returns the number
     * of rows updated (1 if a slot was taken, 0 if full) — the DB-level conditional
     * check makes this safe against two guests confirming the last slot at once.
     */
    @Modifying
    @Query(
        "UPDATE Event e SET e.confirmedCount = e.confirmedCount + 1 " +
            "WHERE e.id = :id AND (e.maxCapacity IS NULL OR e.confirmedCount < :limit)",
    )
    fun reserveSlot(
        @Param("id") id: UUID,
        @Param("limit") limit: Int,
    ): Int

    @Modifying
    @Query("UPDATE Event e SET e.confirmedCount = e.confirmedCount - 1 WHERE e.id = :id AND e.confirmedCount > 0")
    fun releaseSlot(
        @Param("id") id: UUID,
    ): Int
}
