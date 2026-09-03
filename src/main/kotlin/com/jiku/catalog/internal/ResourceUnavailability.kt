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
 * Indisponibilité d'une ressource sur une période (JIKU-84) : congés, absences,
 * maintenance. Période bornée en UTC ; elle prime sur l'horaire hebdomadaire.
 */
@Entity
@Table(name = "resource_unavailability")
class ResourceUnavailability(
    @Column(name = "resource_id", nullable = false, updatable = false)
    val resourceId: UUID,
    @Column(name = "starts_at", nullable = false)
    val startsAt: Instant,
    @Column(name = "ends_at", nullable = false)
    val endsAt: Instant,
    @Column(name = "reason")
    var reason: String?,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
