package com.jiku.invitation.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface GuestRepository : JpaRepository<Guest, UUID> {
    fun findByEventId(eventId: UUID): List<Guest>

    fun countByEventId(eventId: UUID): Long

    fun countByEventIdAndRsvpStatus(
        eventId: UUID,
        rsvpStatus: RsvpStatus,
    ): Long

    fun existsByEventIdAndEmailIgnoreCase(
        eventId: UUID,
        email: String,
    ): Boolean

    fun existsByEventIdAndPhoneNumber(
        eventId: UUID,
        phoneNumber: String,
    ): Boolean

    /**
     * Matches guests of an event whose first name, last name, full name, email or
     * phone contains [pattern] (a pre-lowercased SQL LIKE pattern, e.g. `%ada%`).
     */
    @Query(
        "SELECT g FROM Guest g WHERE g.eventId = :eventId AND (" +
            "LOWER(g.firstName) LIKE :pattern OR " +
            "LOWER(g.lastName) LIKE :pattern OR " +
            "LOWER(CONCAT(g.firstName, ' ', g.lastName)) LIKE :pattern OR " +
            "LOWER(g.email) LIKE :pattern OR " +
            "g.phoneNumber LIKE :pattern)",
    )
    fun search(
        @Param("eventId") eventId: UUID,
        @Param("pattern") pattern: String,
    ): List<Guest>
}
