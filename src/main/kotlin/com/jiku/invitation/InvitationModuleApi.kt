package com.jiku.invitation

import java.util.UUID

/**
 * The invitation module's public API. Other modules (notably checkin) resolve and
 * search guests through this interface only — never the guest entity or repository.
 * Reads are scoped to the current tenant by the persistence-layer tenant filter.
 */
interface InvitationModuleApi {
    fun findGuest(guestId: UUID): GuestInfo?

    /**
     * Finds guests of [eventId] whose name, email or phone matches [query]
     * (case-insensitive substring). Supports the validator's manual, no-ticket
     * check-in path. An empty/blank query returns no results.
     */
    fun searchGuests(
        eventId: UUID,
        query: String,
    ): List<GuestInfo>
}

/**
 * Read-only view of an invited guest, safe to share across module boundaries.
 */
data class GuestInfo(
    val id: UUID,
    val eventId: UUID,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val phoneNumber: String?,
    val rsvpStatus: String,
)
