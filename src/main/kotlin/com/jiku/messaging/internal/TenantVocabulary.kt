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
 * Terme produit surchargé par un tenant (JIKU-91) : ex. key « appointment » →
 * « consultation », « ticket » → « rendez-vous ». La valeur renseignée prend le
 * pas sur le terme par défaut du produit ; l'absence de ligne = terme par défaut.
 */
@Entity
@Table(
    name = "tenant_vocabulary",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_tenant_vocabulary_key", columnNames = ["tenant_id", "key"]),
    ],
)
class TenantVocabulary(
    @Column(name = "key", nullable = false, length = 64)
    val key: String,
    @Column(name = "value", nullable = false, length = 120)
    var value: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
