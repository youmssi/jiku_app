package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ResourceRepository : JpaRepository<Resource, UUID>

interface ResourceAvailabilityRepository : JpaRepository<ResourceAvailability, UUID> {
    fun findByResourceId(resourceId: UUID): List<ResourceAvailability>
}

interface ResourceUnavailabilityRepository : JpaRepository<ResourceUnavailability, UUID> {
    fun findByResourceId(resourceId: UUID): List<ResourceUnavailability>
}
