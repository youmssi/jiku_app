package com.jiku.invitation.internal

import com.jiku.event.InvitationChannel
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface InvitationRepository : JpaRepository<Invitation, UUID> {
    fun findByEventId(eventId: UUID): List<Invitation>

    fun findByEventIdAndStatus(
        eventId: UUID,
        status: InvitationStatus,
    ): List<Invitation>

    fun findByGuestIdAndChannel(
        guestId: UUID,
        channel: InvitationChannel,
    ): Invitation?
}
