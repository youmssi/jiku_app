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
}
