package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TicketTypeRepository : JpaRepository<TicketType, UUID> {
    fun findByEventIdOrderByPositionAsc(eventId: UUID): List<TicketType>

    /**
     * Réserve une place dans la catégorie, si son propre plafond le permet.
     *
     * Même transition atomique conditionnelle que la capacité d'événement : la
     * lecture et l'écriture se font dans une seule instruction, donc deux invités
     * qui confirment la dernière place VIP en même temps ne peuvent pas
     * l'obtenir tous les deux.
     *
     * En HQL, donc le prédicat de tenant s'applique — une requête native
     * réserverait dans la catégorie d'un autre tenant.
     */
    @Modifying
    @Query(
        "UPDATE TicketType t SET t.confirmedCount = t.confirmedCount + 1 " +
            "WHERE t.id = :id AND (t.maxCapacity IS NULL OR t.confirmedCount < t.maxCapacity)",
    )
    fun reserveSlot(
        @Param("id") id: UUID,
    ): Int

    @Modifying
    @Query("UPDATE TicketType t SET t.confirmedCount = t.confirmedCount - 1 WHERE t.id = :id AND t.confirmedCount > 0")
    fun releaseSlot(
        @Param("id") id: UUID,
    ): Int
}
