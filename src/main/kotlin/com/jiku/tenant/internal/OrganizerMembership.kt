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
 * Links a user to an organization with a role (JIKU-48). Like [OrganizerUser]
 * this is identity data resolved before any tenant context exists, so it is not
 * a tenant-scoped entity; [tenantId] is business data here, not a discriminator.
 */
@Entity
@Table(name = "organizer_membership")
class OrganizerMembership(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,
    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    var role: OrganizerRole,
    @Column(name = "invited_by", updatable = false)
    val invitedBy: UUID? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
