package com.jiku.catalog.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Un service proposé par un organisateur (JIKU-85) : ce qu'on réserve (coupe,
 * coloration, séance photo…). Tenant-scopé. Les exigences en ressources et la
 * configuration de la grille lui sont rattachées ; la configuration complète
 * arrive en JIKU-86.
 */
@Entity
@Table(name = "service")
class Service(
    @Column(name = "name", nullable = false, length = 120)
    var name: String,
    /** Fuseau IANA du service : la grille de créneaux y est exprimée. */
    @Column(name = "timezone", nullable = false, length = 64)
    val timezone: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
