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

    /**
     * Réserve une place en respectant **à la fois** la capacité de l'événement et
     * celle de la catégorie d'accès (JIKU-93), dans une seule transaction.
     *
     * Si la catégorie est pleine alors que l'événement ne l'est pas, rien n'est
     * consommé : une place globale prise sans place de catégorie ferait mentir le
     * compteur, et le portier refuserait quelqu'un que le système croit admis.
     *
     * [ticketTypeId] nul revient exactement à [reserveAttendanceSlot].
     */
    fun reserveAttendanceSlot(
        eventId: UUID,
        ticketTypeId: UUID?,
    ): Boolean

    /** Libère une place, et celle de la catégorie si le billet en portait une. */
    fun releaseAttendanceSlot(
        eventId: UUID,
        ticketTypeId: UUID?,
    )

    /** Catégories d'accès d'un événement, vides si l'organisateur n'en a pas défini. */
    fun ticketTypes(eventId: UUID): List<TicketTypeInfo>

    /**
     * Déplace la place d'un invité **déjà confirmé** d'une catégorie à une autre,
     * sans toucher au compteur global : la personne était déjà comptée dans la
     * salle, elle l'est toujours, seule sa catégorie change.
     *
     * Renvoie `false` si la catégorie d'arrivée est pleine ; dans ce cas rien n'a
     * bougé et l'invité reste dans sa catégorie d'origine.
     *
     * `from` ou `to` à `null` couvre l'entrée dans une catégorie depuis « aucune »
     * et la sortie vers « aucune ».
     */
    fun moveTicketTypeSlot(
        from: UUID?,
        to: UUID?,
    ): Boolean

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
