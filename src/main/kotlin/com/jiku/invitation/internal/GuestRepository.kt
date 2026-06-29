package com.jiku.invitation.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GuestRepository : JpaRepository<Guest, UUID> {
    fun findByEventId(eventId: UUID): List<Guest>

    fun existsByEventIdAndEmailIgnoreCase(
        eventId: UUID,
        email: String,
    ): Boolean

    fun existsByEventIdAndPhoneNumber(
        eventId: UUID,
        phoneNumber: String,
    ): Boolean
}
