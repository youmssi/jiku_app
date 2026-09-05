package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ResourceRepository : JpaRepository<Resource, UUID> {
    /** Ressources actives d'un type, dans un ordre stable pour l'affectation. */
    fun findByActiveTrueAndTypeOrderByNameAsc(type: ResourceType): List<Resource>

    /** Nombre de ressources actives du tenant courant (JIKU-90). */
    fun countByActiveTrue(): Long
}

interface ResourceAvailabilityRepository : JpaRepository<ResourceAvailability, UUID> {
    fun findByResourceId(resourceId: UUID): List<ResourceAvailability>
}

interface ResourceUnavailabilityRepository : JpaRepository<ResourceUnavailability, UUID> {
    fun findByResourceId(resourceId: UUID): List<ResourceUnavailability>
}
