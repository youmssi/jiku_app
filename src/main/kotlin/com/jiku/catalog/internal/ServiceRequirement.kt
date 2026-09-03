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
import jakarta.persistence.UniqueConstraint
import java.util.UUID

/**
 * Une exigence en ressources d'un service (JIKU-85) : un type de ressource et la
 * quantité requise pour servir un client. Un salon peut exiger 1 personne ET 1
 * lieu : le créneau n'est réservable que si chaque exigence est satisfaite.
 */
@Entity
@Table(
    name = "service_requirement",
    uniqueConstraints = [UniqueConstraint(name = "uq_service_requirement_type", columnNames = ["service_id", "resource_type"])],
)
class ServiceRequirement(
    @Column(name = "service_id", nullable = false, updatable = false)
    val serviceId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 32)
    val type: ResourceType,
    @Column(name = "quantity", nullable = false)
    val quantity: Int,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
