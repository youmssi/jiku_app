package com.jiku.catalog.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalTime
import java.util.UUID

/**
 * Horaire hebdomadaire d'une ressource (JIKU-84). Locaux au fuseau de la
 * ressource : `dayOfWeek` suit ISO-8601 (1 = lundi … 7 = dimanche), `start`/`end`
 * sont des heures locales. Une plage horaire ouverte (start <= t < end) rend la
 * ressource disponible.
 */
@Entity
@Table(name = "resource_availability")
class ResourceAvailability(
    @Column(name = "resource_id", nullable = false, updatable = false)
    val resourceId: UUID,
    @Column(name = "day_of_week", nullable = false)
    val dayOfWeek: Int,
    @Column(name = "start_time", nullable = false)
    val start: LocalTime,
    @Column(name = "end_time", nullable = false)
    val end: LocalTime,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
