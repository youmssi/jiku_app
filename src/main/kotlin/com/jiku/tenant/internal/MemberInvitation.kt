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
 * An invitation to join an organization with a role (JIKU-50). Identity-level
 * like [OrganizerMembership] — the accept flow resolves it by token before any
 * tenant context exists. Re-inviting the same address refreshes the token and
 * expiry on the pending row instead of stacking duplicates; the row survives
 * acceptance or revocation as the record of who invited whom.
 */
@Entity
@Table(name = "member_invitation")
class MemberInvitation(
    @Column(name = "tenant_id", nullable = false, updatable = false)
    val tenantId: String,
    @Column(name = "email", nullable = false, updatable = false)
    val email: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    var role: OrganizerRole,
    @Column(name = "token_hash", nullable = false, unique = true)
    var tokenHash: String,
    @Column(name = "invited_by", nullable = false, updatable = false)
    val invitedBy: UUID,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: InvitationStatus = InvitationStatus.PENDING

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

enum class InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
}
