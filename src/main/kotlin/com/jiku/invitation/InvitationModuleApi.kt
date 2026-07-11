package com.jiku.invitation

import java.time.Instant
import java.util.UUID

/**
 * The invitation module's public API. Other modules (notably checkin) resolve and
 * search guests through this interface only — never the guest entity or repository.
 * Reads are scoped to the current tenant by the persistence-layer tenant filter.
 */
interface InvitationModuleApi {
    fun findGuest(guestId: UUID): GuestInfo?

    /** All guests of an event (for an organizer dashboard, export, or offline roster). */
    fun listGuests(eventId: UUID): List<GuestInfo>

    /** Aggregate guest counts for an event's dashboard. */
    fun guestStats(eventId: UUID): GuestStats

    /**
     * Finds guests of [eventId] whose name, email or phone matches [query]
     * (case-insensitive substring). Supports the validator's manual, no-ticket
     * check-in path. An empty/blank query returns no results.
     */
    fun searchGuests(
        eventId: UUID,
        query: String,
    ): List<GuestInfo>

    /** Counts of successfully sent invitations for an event, broken down by channel. */
    fun sentInvitationCounts(eventId: UUID): SentInvitationCounts

    /** Delivery status breakdown per channel for an event's analytics view. */
    fun channelBreakdown(eventId: UUID): List<ChannelBreakdown>
}

/** Successfully sent (status SENT) invitation counts for an event, per channel. */
data class SentInvitationCounts(
    val email: Long,
    val whatsapp: Long,
)

/** Per-channel invitation delivery status counts, for the analytics dashboard. */
data class ChannelBreakdown(
    val channel: String,
    val sent: Long,
    val failed: Long,
    val pending: Long,
)

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
    val createdAt: Instant,
)

/**
 * Aggregate guest figures for an event. [invited] is the number of distinct guests
 * who have received at least one successfully sent invitation; [pending] is guests
 * who have not yet responded.
 */
data class GuestStats(
    val total: Long,
    val invited: Long,
    val confirmed: Long,
    val declined: Long,
    val pending: Long,
)
