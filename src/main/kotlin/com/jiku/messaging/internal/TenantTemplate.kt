package com.jiku.messaging.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/**
 * Gabarit client surchargé par un tenant (JIKU-91) : contenu complet d'un message
 * (e-mail HTML ou WhatsApp texte) avec ses variables {{…}}. En l'absence de ligne,
 * le gabarit par défaut du produit s'applique — un nouveau métier s'ouvre sans
 * modification de code. Le corps est stocké brut ; l'échappement et la validation
 * à l'envoi restent au module messaging.
 */
@Entity
@Table(
    name = "tenant_template",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_tenant_template_key",
            columnNames = ["tenant_id", "name", "channel"],
        ),
    ],
)
class TenantTemplate(
    @Column(name = "name", nullable = false, length = 64)
    val name: String,
    @Column(name = "channel", nullable = false, length = 16)
    val channel: String,
    @Column(name = "body", nullable = false)
    var body: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "active", nullable = false)
    var active: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
