package com.jiku.ticket.internal

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface TicketRepository : JpaRepository<Ticket, UUID> {
    fun findByGuestId(guestId: UUID): Ticket?

    fun findByTicketCode(ticketCode: String): Ticket?

    fun countByEventIdAndStatus(
        eventId: UUID,
        status: TicketStatus,
    ): Long

    fun countByEventIdAndStatusIn(
        eventId: UUID,
        statuses: Collection<TicketStatus>,
    ): Long

    fun findByEventId(eventId: UUID): List<Ticket>

    /** Billet(s) d'un créneau de service — lecture d'annulation (JIKU-89). */
    fun findByServiceIdAndStartsAtAndStatusAndKind(
        serviceId: UUID,
        startsAt: Instant,
        status: TicketStatus,
        kind: TicketKind,
    ): List<Ticket>

    /** Rows of [checkedInBy label, count] for an event's checked-in tickets. */
    @Query(
        "SELECT t.checkedInBy, COUNT(t) FROM Ticket t " +
            "WHERE t.eventId = :eventId AND t.status = com.jiku.ticket.internal.TicketStatus.CHECKED_IN " +
            "AND t.checkedInBy IS NOT NULL GROUP BY t.checkedInBy",
    )
    fun checkInCountsByLabel(
        @Param("eventId") eventId: UUID,
    ): List<Array<Any>>

    /**
     * First-timestamp-wins reconciliation: rewrites an already-checked-in ticket to
     * an earlier scan, only if the recorded check-in is strictly later. Returns the
     * number of rows updated (1 if this scan now owns the record, 0 otherwise).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.checkedInAt = :at, t.checkedInBy = :by " +
            "WHERE t.id = :id AND t.status = com.jiku.ticket.internal.TicketStatus.CHECKED_IN " +
            "AND t.checkedInAt > :at",
    )
    fun reassignEarlierCheckIn(
        @Param("id") id: UUID,
        @Param("at") at: Instant,
        @Param("by") by: String,
    ): Int

    /**
     * Atomically transitions a ticket from ISSUED to CHECKED_IN, stamping the
     * moment and the validator. Returns the number of rows updated (1 on the first
     * check-in, 0 if it was already checked in or cancelled) — the DB-level status
     * guard makes this safe against two validators scanning the same ticket at once.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.status = com.jiku.ticket.internal.TicketStatus.CHECKED_IN, " +
            "t.checkedInAt = :at, t.checkedInBy = :by " +
            "WHERE t.id = :id AND t.status = com.jiku.ticket.internal.TicketStatus.ISSUED",
    )
    fun checkIn(
        @Param("id") id: UUID,
        @Param("at") at: Instant,
        @Param("by") by: String,
    ): Int

    /**
     * La ligne du jour (JIKU-88) : tickets de service dont la journée tombe dans
     * la fenêtre — créneau pour un rendez-vous, arrivée pour un sans-rendez-vous.
     * Triés par heure d'affichage : un sans-rendez-vous s'intercale à l'heure de
     * son arrivée entre les rendez-vous, pas en fin de liste.
     */
    @Query(
        "SELECT t FROM Ticket t WHERE t.serviceId = :serviceId AND t.kind IN :kinds AND " +
            "((t.kind = com.jiku.ticket.internal.TicketKind.APPOINTMENT AND t.startsAt IS NOT NULL " +
            "AND t.startsAt >= :from AND t.startsAt < :to) " +
            "OR (t.kind = com.jiku.ticket.internal.TicketKind.WALK_IN AND t.arrivedAt IS NOT NULL " +
            "AND t.arrivedAt >= :from AND t.arrivedAt < :to)) " +
            "ORDER BY CASE WHEN t.kind = com.jiku.ticket.internal.TicketKind.APPOINTMENT " +
            "THEN t.startsAt ELSE t.arrivedAt END ASC NULLS LAST, t.dayRank ASC NULLS LAST",
    )
    fun findServiceDay(
        @Param("serviceId") serviceId: UUID,
        @Param("kinds") kinds: Set<TicketKind>,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Ticket>

    /**
     * Arrivée au comptoir (JIKU-88) : ISSUED → WAITING, horodatée, avec son rang
     * du jour. La garde sur l'état rend la transition atomique (double scan).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.status = com.jiku.ticket.internal.TicketStatus.WAITING, " +
            "t.arrivedAt = :at, t.dayRank = :rank " +
            "WHERE t.id = :id AND t.serviceId = :serviceId AND t.status = com.jiku.ticket.internal.TicketStatus.ISSUED",
    )
    fun arriveLine(
        @Param("id") id: UUID,
        @Param("serviceId") serviceId: UUID,
        @Param("at") at: Instant,
        @Param("rank") rank: Int,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.status = com.jiku.ticket.internal.TicketStatus.CALLED " +
            "WHERE t.id = :id AND t.serviceId = :serviceId AND t.status = com.jiku.ticket.internal.TicketStatus.WAITING",
    )
    fun callLine(
        @Param("id") id: UUID,
        @Param("serviceId") serviceId: UUID,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.status = com.jiku.ticket.internal.TicketStatus.IN_SERVICE " +
            "WHERE t.id = :id AND t.serviceId = :serviceId AND " +
            "(t.status = com.jiku.ticket.internal.TicketStatus.CALLED " +
            "OR t.status = com.jiku.ticket.internal.TicketStatus.NO_SHOW)",
    )
    fun presentLine(
        @Param("id") id: UUID,
        @Param("serviceId") serviceId: UUID,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.status = com.jiku.ticket.internal.TicketStatus.DONE " +
            "WHERE t.id = :id AND t.serviceId = :serviceId AND t.status = com.jiku.ticket.internal.TicketStatus.IN_SERVICE",
    )
    fun finishLine(
        @Param("id") id: UUID,
        @Param("serviceId") serviceId: UUID,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE Ticket t SET t.status = com.jiku.ticket.internal.TicketStatus.NO_SHOW " +
            "WHERE t.id = :id AND t.serviceId = :serviceId AND t.status = com.jiku.ticket.internal.TicketStatus.CALLED",
    )
    fun noShowLine(
        @Param("id") id: UUID,
        @Param("serviceId") serviceId: UUID,
    ): Int
}

/**
 * Compteur de rang par (service, journée locale). Les lectures de l'incrément se
 * font sous verrou pessimiste ; la création de ligne se fait en propre
 * transaction par [TicketDayRankAllocator].
 */
interface TicketDayCounterRepository : JpaRepository<TicketDayCounter, UUID> {
    /**
     * Prend le verrou d'écriture du compteur du service pour la journée, tenu
     * jusqu'à l'engagement de la transaction d'arrivée. Deux arrivées
     * concurrentes se mettent donc en file plutôt que de lire le même rang.
     *
     * Reste une requête dérivée et non du SQL natif pour que le prédicat
     * `@TenantId` de Hibernate s'applique — une lecture native trouverait le
     * compteur d'un autre tenant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TicketDayCounter c where c.serviceId = :serviceId and c.day = :day")
    fun findForUpdate(
        @Param("serviceId") serviceId: UUID,
        @Param("day") day: LocalDate,
    ): TicketDayCounter?

    /**
     * Vérification d'existence sans verrou, utilisée uniquement à la création de
     * la ligne d'une nouvelle journée. Prendre le verrou ici mettrait en file
     * chaque arrivée derrière le chemin de création plutôt que derrière
     * l'incrément. HQL et non SQL natif, pour le prédicat tenant.
     */
    @Query("select c from TicketDayCounter c where c.serviceId = :serviceId and c.day = :day")
    fun findExisting(
        @Param("serviceId") serviceId: UUID,
        @Param("day") day: LocalDate,
    ): TicketDayCounter?
}
