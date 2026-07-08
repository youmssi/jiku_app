package com.jiku.invitation.internal

import com.jiku.event.InvitationChannel
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

    fun findByGuestIdAndChannel(
        guestId: UUID,
        channel: InvitationChannel,
    ): Invitation?
}
