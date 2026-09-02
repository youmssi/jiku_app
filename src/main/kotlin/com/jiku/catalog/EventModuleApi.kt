package com.jiku.catalog

import java.time.Instant
import java.util.UUID

/**
 * The event module's public API. Other modules (invitation, ticketing, checkin)
 * read events through this interface only. Reads are scoped to the current tenant
 * by the persistence-layer tenant filter, so an event is only visible within its
 * own tenant's context.
 *
 * Kept deliberately minimal and stable — many modules depend on it.
 */
interface EventModuleApi {
    fun findEvent(eventId: UUID): EventInfo?

    /**
     * Events across all tenants whose date is before [cutoff] — the retention job's
     * (JIKU-37) worklist. Intentionally cross-tenant: it is a platform maintenance
     * task, and the caller binds each event's own tenant before touching its data.
     */
    fun eventsPastRetention(cutoff: Instant): List<RetentionCandidate>

    /**
     * Atomically reserves one attendance slot, honoring capacity and overbooking.
     * Returns true if a slot was taken, false if the event is full.
     */
    fun reserveAttendanceSlot(eventId: UUID): Boolean

    /** Releases a previously reserved attendance slot (e.g. a guest who declines). */
    fun releaseAttendanceSlot(eventId: UUID)

    /**
     * Règle de quorum de l'événement, ou null si l'organisateur n'en a pas
     * défini (JIKU-94).
     *
     * Un quorum compte les personnes **présentes**, pas les confirmations : une
     * assemblée délibère avec ceux qui sont dans la salle. Ce module ne connaît
     * ni les inscrits ni les présents, donc l'appelant fournit les deux.
     */
    fun quorum(
        eventId: UUID,
        totalGuests: Long,
        checkedIn: Long,
    ): QuorumInfo?

    /**
     * Horodate l'atteinte du quorum, une seule fois. Sans effet si elle est déjà
     * enregistrée : la date d'atteinte est la valeur probante et ne se réécrit
     * jamais.
     */
    fun markQuorumReached(eventId: UUID)

    /**
     * Creates a draft event pre-filled from a verified booking (JIKU-55), under
     * the tenant bound in the current [com.jiku.shared.TenantContext]. Returns
     * the new event's id. The organizer completes and publishes it themselves —
     * this only spares them a blank starting point.
     */
    fun createDraftEvent(
        name: String,
        timezone: String,
        startDateTime: Instant?,
        invitationChannels: Set<InvitationChannel>,
    ): UUID
}
