package com.jiku.tenant.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A person's login identity. Deliberately **not** a tenant-scoped entity: a
 * login resolves a user by email before any tenant context exists, so identity
 * is a cross-tenant concern. Which organizations the user belongs to — and with
 * what role — lives in [OrganizerMembership] (JIKU-48).
 */
@Entity
@Table(name = "organizer_user")
class OrganizerUser(
    @Column(name = "email", nullable = false, unique = true)
    val email: String,
    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** Set by the JIKU-49 verification flow; gates organization creation. */
    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false

    /** Refresh tokens issued before this instant are rejected (password reset). */
    @Column(name = "password_changed_at")
    var passwordChangedAt: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
