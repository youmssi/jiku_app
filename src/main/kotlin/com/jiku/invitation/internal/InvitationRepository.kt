package com.jiku.invitation.internal

import com.jiku.catalog.InvitationChannel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface InvitationRepository : JpaRepository<Invitation, UUID> {
    fun findByEventId(eventId: UUID): List<Invitation>

    /** Distinct guests who have received at least one successfully sent invitation. */
    @Query(
        "SELECT COUNT(DISTINCT i.guestId) FROM Invitation i " +
            "WHERE i.eventId = :eventId AND i.status = com.jiku.invitation.internal.InvitationStatus.SENT",
    )
    fun countInvitedGuests(
        @Param("eventId") eventId: UUID,
    ): Long

    fun findByEventIdAndStatus(
        eventId: UUID,
        status: InvitationStatus,
    ): List<Invitation>

    /** Count of successfully sent invitations for an event on a given channel. */
    fun countByEventIdAndChannelAndStatus(
        eventId: UUID,
        channel: InvitationChannel,
        status: InvitationStatus,
    ): Long

    fun findByGuestIdAndChannel(
        guestId: UUID,
        channel: InvitationChannel,
    ): Invitation?

    fun findByGuestId(guestId: UUID): List<Invitation>

    fun deleteByGuestId(guestId: UUID)

    /**
     * Every QUEUED invitation across every tenant (JIKU-61/62) — native SQL so
     * the tenant filter does not apply, since [NotificationQueueSweepJob] must
     * sweep the whole platform, not just whatever tenant happens to be bound.
     */
    @Query(value = "SELECT id, tenant_id AS tenantId FROM invitation WHERE status = 'QUEUED'", nativeQuery = true)
    fun findGlobalQueued(): List<QueuedInvitationRef>

    /**
     * Invitations handed to the sender before [before] and still PENDING, across
     * every tenant (JIKU-215): their send was lost with a restart. One never
     * handed over counts from its creation.
     */
    @Query(
        value =
            "SELECT id, tenant_id AS tenantId FROM invitation WHERE status = 'PENDING' " +
                "AND COALESCE(dispatched_at, created_at) < :before",
        nativeQuery = true,
    )
    fun findGlobalStalePending(
        @Param("before") before: Instant,
    ): List<QueuedInvitationRef>
}

interface QueuedInvitationRef {
    val id: UUID
    val tenantId: String
}
