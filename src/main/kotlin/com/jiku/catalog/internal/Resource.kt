package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Une ressource mobilisable pour un créneau (JIKU-84) : une personne, un lieu ou
 * un équipement. Tenant-scopée. `active=false` désactive la ressource : elle ne
 * bloque aucun créneau existant et n'est plus proposée à la réservation.
 */
@Entity
@Table(name = "resource")
class Resource(
    @Column(name = "name", nullable = false, length = 120)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    var type: ResourceType,
    /** Fuseau IANA de la ressource : ses horaires sont locaux à ce fuseau. */
    @Column(name = "timezone", nullable = false, length = 64)
    var timezone: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
