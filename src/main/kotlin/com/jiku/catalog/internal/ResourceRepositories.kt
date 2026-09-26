package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ResourceRepository : JpaRepository<Resource, UUID> {
    /** Ressources actives d'un type, dans un ordre stable pour l'affectation. */
    fun findByActiveTrueAndTypeOrderByNameAsc(type: ResourceType): List<Resource>

    /**
     * Ressources actives des types donnés, triées par nom — le chargement en une
     * passe de la grille du jour (P1) : les sous-listes par type restent triées.
     */
    fun findByActiveTrueAndTypeInOrderByNameAsc(types: Collection<ResourceType>): List<Resource>

    /** Nombre de ressources actives du tenant courant (JIKU-90). */
    fun countByActiveTrueAndType(type: ResourceType): Long
}

/**
 * The professional a booking was actually assigned (JIKU-87): the person among
 * its reserved resources, or null when the service needs no person.
 */
internal fun ResourceRepository.professionalAmong(resourceIds: Collection<UUID>): String? =
    findAllById(resourceIds).filter { it.type == ResourceType.PERSON }.minByOrNull { it.name }?.name

interface ResourceAvailabilityRepository : JpaRepository<ResourceAvailability, UUID> {
    fun findByResourceId(resourceId: UUID): List<ResourceAvailability>

    /** Horaires hebdomadaires de plusieurs ressources en une passe (P1). */
    fun findByResourceIdIn(resourceIds: Collection<UUID>): List<ResourceAvailability>
}

interface ResourceUnavailabilityRepository : JpaRepository<ResourceUnavailability, UUID> {
    fun findByResourceId(resourceId: UUID): List<ResourceUnavailability>

    /**
     * Indisponibilités des ressources données qui chevauchent la fenêtre — le
     * préchargement de la grille du jour (P1). HQL, jamais natif : le prédicat de
     * tenant s'applique.
     */
    @Query(
        "select u from ResourceUnavailability u where u.resourceId in :ids " +
            "and u.startsAt < :end and u.endsAt > :start",
    )
    fun findOverlappingByResourceIdIn(
        @Param("ids") ids: Collection<UUID>,
        @Param("start") start: Instant,
        @Param("end") end: Instant,
    ): List<ResourceUnavailability>
}
