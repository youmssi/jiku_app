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
 * An organizer's login identity. Deliberately **not** a tenant-scoped entity: a
 * login resolves a user by email before any tenant context exists, so identity is
 * a cross-tenant concern. [tenantId] records the tenant this organizer owns.
 */
@Entity
@Table(name = "organizer_user")
class OrganizerUser(
    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,
    @Column(name = "email", nullable = false, unique = true)
    val email: String,
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    val role: OrganizerRole = OrganizerRole.ORGANIZER_ADMIN,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
