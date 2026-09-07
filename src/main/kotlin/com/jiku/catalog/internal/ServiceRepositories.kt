package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ServiceRepository : JpaRepository<Service, UUID>

interface ServiceRequirementRepository : JpaRepository<ServiceRequirement, UUID> {
    fun findByServiceId(serviceId: UUID): List<ServiceRequirement>
}

interface ServiceReservationRepository : JpaRepository<ServiceReservation, UUID> {
    fun findByBookingTokenHash(tokenHash: String): List<ServiceReservation>

    /**
     * Occupations des ressources données sur une fenêtre — le préchargement de la
     * grille du jour (P1) : une confirmation, ou une demande en attente non
     * expirée (le même prédicat que [countOccupying], évalué en mémoire ensuite).
     */
    @Query(
        "SELECT r FROM ServiceReservation r " +
            "WHERE r.resourceId IN :resourceIds AND r.startsAt < :endsAt AND r.endsAt > :startsAt " +
            "AND (r.status = com.jiku.catalog.internal.ServiceReservationStatus.CONFIRMED " +
            "OR (r.status = com.jiku.catalog.internal.ServiceReservationStatus.PENDING " +
            "AND (r.heldUntil IS NULL OR r.heldUntil > :now)))",
    )
    fun findOccupyingBetweenResources(
        @Param("resourceIds") resourceIds: Collection<UUID>,
        @Param("startsAt") startsAt: Instant,
        @Param("endsAt") endsAt: Instant,
        @Param("now") now: Instant,
    ): List<ServiceReservation>

    /**
     * Demandes en attente de confirmation d'un service sur une fenêtre de jour
     * (JIKU-87/88). Triées par créneau puis par date de demande pour qu'un écran
     * de comptoir montre les plus anciennes d'abord.
     */
    @Query(
        "SELECT r FROM ServiceReservation r " +
            "WHERE r.serviceId = :serviceId " +
            "AND r.status = com.jiku.catalog.internal.ServiceReservationStatus.PENDING " +
            "AND r.bookingTokenHash IS NOT NULL " +
            "AND r.startsAt >= :from AND r.startsAt < :to " +
            "ORDER BY r.startsAt ASC, r.createdAt ASC",
    )
    fun findPendingBetween(
        @Param("serviceId") serviceId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<ServiceReservation>

    /**
     * Confirme une demande en attente (JIKU-87) : toutes les lignes de ressource
     * partageant le jeton client passent PENDING → CONFIRMED et libèrent leur
     * hold. Conditionnel : une demande déjà traitée ou expirée ne fait rien.
     * Renvoie le nombre de lignes confirmées.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE ServiceReservation r " +
            "SET r.status = com.jiku.catalog.internal.ServiceReservationStatus.CONFIRMED, r.heldUntil = NULL " +
            "WHERE r.bookingTokenHash = :tokenHash " +
            "AND r.status = com.jiku.catalog.internal.ServiceReservationStatus.PENDING " +
            "AND (r.heldUntil IS NULL OR r.heldUntil > :now)",
    )
    fun confirmByTokenHash(
        @Param("tokenHash") tokenHash: String,
        @Param("now") now: Instant,
    ): Int

    /** Refuse une demande en attente (JIKU-87) : ses lignes sont supprimées, la case se libère. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ServiceReservation r WHERE r.bookingTokenHash = :tokenHash")
    fun deleteByTokenHash(
        @Param("tokenHash") tokenHash: String,
    ): Int

    /**
     * Nombre de réservations qui occupent [resourceId] sur la fenêtre
     * [startsAt]..[endsAt] : une confirmation, ou une demande en attente non
     * expirée. Les demandes expirées sont ignorées ici — elles seront purgées,
     * mais ne bloquent déjà plus.
     */
    @Query(
        "SELECT COUNT(r) FROM ServiceReservation r " +
            "WHERE r.resourceId = :resourceId AND r.startsAt < :endsAt AND r.endsAt > :startsAt " +
            "AND (r.status = com.jiku.catalog.internal.ServiceReservationStatus.CONFIRMED " +
            "OR (r.status = com.jiku.catalog.internal.ServiceReservationStatus.PENDING " +
            "AND (r.heldUntil IS NULL OR r.heldUntil > :now)))",
    )
    fun countOccupying(
        @Param("resourceId") resourceId: UUID,
        @Param("startsAt") startsAt: Instant,
        @Param("endsAt") endsAt: Instant,
        @Param("now") now: Instant,
    ): Long

    /** Libère les demandes en attente arrivées à expiration : la case redevient réservable. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "DELETE FROM ServiceReservation r WHERE r.status = com.jiku.catalog.internal.ServiceReservationStatus.PENDING " +
            "AND r.heldUntil IS NOT NULL AND r.heldUntil <= :now",
    )
    fun deleteExpiredHolds(
        @Param("now") now: Instant,
    ): Int

    /**
     * Tenants ayant des demandes en attente arrivées à expiration — la liste de
     * travail du balayage plateforme. Native et volontairement cross-tenant : le
     * balayage lie ensuite le tenant de chaque ligne avant la suppression, comme
     * les autres jobs plateforme.
     */
    @Query(
        value =
            "SELECT DISTINCT tenant_id FROM service_reservation " +
                "WHERE status = 'PENDING' AND held_until IS NOT NULL AND held_until <= :now",
        nativeQuery = true,
    )
    fun expiredHoldTenantIds(
        @Param("now") now: Instant,
    ): List<String>
}
