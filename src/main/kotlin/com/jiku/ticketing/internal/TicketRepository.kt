package com.jiku.ticketing.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TicketRepository : JpaRepository<Ticket, UUID> {
    fun findByGuestId(guestId: UUID): Ticket?

    fun findByTicketCode(ticketCode: String): Ticket?

    fun countByEventIdAndStatus(
        eventId: UUID,
        status: TicketStatus,
    ): Long

    fun countByEventIdAndStatusIn(
        eventId: UUID,
        statuses: Collection<TicketStatus>,
    ): Long

    fun findByEventId(eventId: UUID): List<Ticket>

    /**
     * First-timestamp-wins reconciliation: rewrites an already-checked-in ticket to
     * an earlier scan, only if the recorded check-in is strictly later. Returns the
     * number of rows updated (1 if this scan now owns the record, 0 otherwise).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.checkedInAt = :at, t.checkedInBy = :by " +
            "WHERE t.id = :id AND t.status = com.jiku.ticketing.internal.TicketStatus.CHECKED_IN " +
            "AND t.checkedInAt > :at",
    )
    fun reassignEarlierCheckIn(
        @Param("id") id: UUID,
        @Param("at") at: Instant,
        @Param("by") by: String,
    ): Int

    /**
     * Atomically transitions a ticket from ISSUED to CHECKED_IN, stamping the
     * moment and the validator. Returns the number of rows updated (1 on the first
     * check-in, 0 if it was already checked in or cancelled) — the DB-level status
     * guard makes this safe against two validators scanning the same ticket at once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.status = com.jiku.ticketing.internal.TicketStatus.CHECKED_IN, " +
            "t.checkedInAt = :at, t.checkedInBy = :by " +
            "WHERE t.id = :id AND t.status = com.jiku.ticketing.internal.TicketStatus.ISSUED",
    )
    fun checkIn(
        @Param("id") id: UUID,
        @Param("at") at: Instant,
        @Param("by") by: String,
    ): Int
}
