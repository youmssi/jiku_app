package com.jiku.invitation.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface GuestRepository : JpaRepository<Guest, UUID> {
    fun findByEventId(eventId: UUID): List<Guest>

    /** Titulaires de rendez-vous, sans événement (JIKU-87). */
    fun findAllByEventIdIsNull(): List<Guest>

    /** Scopes a guest lookup to the event in the URL, so one event's controller can never act on another's guest. */
    fun findByIdAndEventId(
        id: UUID,
        eventId: UUID,
    ): Guest?

    /** Guests of an event whose personal data has not yet been anonymized (JIKU-37). */
    fun findByEventIdAndPersonalDataErasedFalse(eventId: UUID): List<Guest>

    fun countByEventId(eventId: UUID): Long

    /** Dates de création des invités d'un événement (projection pour l'analytique). */
    @Query("select g.createdAt from Guest g where g.eventId = :eventId order by g.createdAt")
    fun findCreatedAtByEventId(
        @Param("eventId") eventId: UUID,
    ): List<Instant>

    /**
     * Guests of an event excluding those superseded by a transfer (JIKU-64), so the
     * headline total stays equal to confirmed + declined + pending. The transferred
     * row is kept for the audit trail, not counted as a second attendee.
     */
    fun countByEventIdAndRsvpStatusNot(
        eventId: UUID,
        rsvpStatus: RsvpStatus,
    ): Long

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

    fun countByTicketTypeId(ticketTypeId: java.util.UUID): Long
}
