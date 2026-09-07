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
 * Lien d'accès du personnel à la console de ligne du jour d'un service (JIKU-88),
 * calqué sur le patron Validator : la ligne est un jeton signé, cette ligne
 * tenant-scopée gouverne la révocation immédiate et porte un [label] lisible.
 * Aucun compte utilisateur — le personnel du comptoir est volontairement léger.
 */
@Entity
@Table(name = "service_staff")
class ServiceStaff(
    @Column(name = "service_id", nullable = false, updatable = false)
    val serviceId: UUID,
    @Column(name = "label", nullable = false, length = 80)
    var label: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "revoked", nullable = false)
    var revoked: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    fun revoke(at: Instant = Instant.now()) {
        if (!revoked) {
            revoked = true
            revokedAt = at
        }
    }
}
