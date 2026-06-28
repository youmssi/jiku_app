package com.jiku.tenant.internal

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
 * A tenant space owned by an organizer. This is the tenant *definition*, not
 * tenant-scoped data, so it is intentionally not a [com.jiku.shared.BaseTenantEntity].
 */
@Entity
@Table(name = "tenant")
class Tenant(
    @Column(name = "name", nullable = false)
    val name: String,
    @Column(name = "contact_email", nullable = false, unique = true)
    val contactEmail: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    val status: TenantStatus = TenantStatus.ACTIVE,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
