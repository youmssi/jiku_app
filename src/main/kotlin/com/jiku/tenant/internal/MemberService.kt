package com.jiku.tenant.internal

import com.jiku.shared.MemberInvitationNotice
import com.jiku.shared.MessageLanguage
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Organization member management (JIKU-50): invitations, roles, removal. Caller
 * authority is always re-derived from the membership table, never trusted from
 * the token — a role may have changed since the token was issued. Two invariants
 * hold everywhere: only an OWNER touches OWNER-ness, and the last OWNER can
 * neither be demoted nor removed.
 */
@Service
class MemberService(
    private val users: OrganizerUserRepository,
    private val memberships: OrganizerMembershipRepository,
    private val invitations: MemberInvitationRepository,
    private val tenants: TenantRepository,
    private val membershipGate: MembershipAccessGateAdapter,
    private val properties: AccountProperties,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(readOnly = true)
    fun listMembers(tenantId: String): List<MemberView> {
        val all = memberships.findByTenantIdOrderByCreatedAtAsc(tenantId)
        val emails = users.findAllById(all.map { it.userId }).associate { it.id to it.email }
        return all.map {
            MemberView(
                userId = it.userId,
                email = emails[it.userId].orEmpty(),
                role = it.role.name,
                joinedAt = it.createdAt,
            )
        }
    }

    @Transactional(readOnly = true)
    fun listInvitations(tenantId: String): List<InvitationView> =
        invitations
            .findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, InvitationStatus.PENDING)
            .map { InvitationView(requireNotNull(it.id), it.email, it.role.name, it.expiresAt, it.createdAt) }

    /**
     * Invites an address with a role. Re-inviting a pending address refreshes its
     * token and expiry (idempotent — never a second pending row); OWNER is granted
     * through a later role change by an owner, never at the door.
     */
    @Transactional
    fun invite(
        callerId: String,
        tenantId: String,
        request: InviteMemberRequest,
    ): InvitationView {
        val caller = requireCaller(callerId, tenantId)
        if (!caller.user.emailVerified) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your email address before inviting members")
        }
        val role = parseRole(request.role)
        if (role == OrganizerRole.OWNER) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite as ADMIN or MEMBER; ownership is granted by a role change")
        }
        val email = request.email.trim()
        users.findByEmail(email)?.let { existing ->
            if (memberships.findByUserIdAndTenantId(requireNotNull(existing.id), tenantId) != null) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "This person is already a member of the organization")
            }
        }

        val raw = Tokens.generate()
        val expiresAt = Instant.now().plus(properties.memberInvitationTtl)
        val invitation =
            invitations.findByTenantIdAndEmailIgnoreCaseAndStatus(tenantId, email, InvitationStatus.PENDING)?.apply {
                this.role = role
                tokenHash = Tokens.hash(raw)
                this.expiresAt = expiresAt
            } ?: MemberInvitation(
                tenantId = tenantId,
                email = email,
                role = role,
                tokenHash = Tokens.hash(raw),
                invitedBy = caller.membership.userId,
                expiresAt = expiresAt,
            )
        val saved = invitations.saveAndFlush(invitation)

        events.publishEvent(
            MemberInvitationNotice(
                email = email,
                organizationName = tenantName(tenantId),
                inviterEmail = caller.user.email,
                role = role.name,
                actionUrl = "${properties.appBaseUrl}/invitations/accept?token=$raw",
                language = tenantLanguage(tenantId),
            ),
        )
        return InvitationView(requireNotNull(saved.id), saved.email, saved.role.name, saved.expiresAt, saved.createdAt)
    }

    @Transactional
    fun revokeInvitation(
        callerId: String,
        tenantId: String,
        invitationId: UUID,
    ) {
        requireCaller(callerId, tenantId)
        val invitation =
            invitations
                .findById(invitationId)
                .filter { it.tenantId == tenantId }
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found") }
        if (invitation.status != InvitationStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Only a pending invitation can be revoked")
        }
        invitation.status = InvitationStatus.REVOKED
        invitations.save(invitation)
    }

    /** The accept page shows what is being joined before the user commits. */
    @Transactional(readOnly = true)
    fun preview(rawToken: String): InvitationPreview {
        val invitation = pendingByToken(rawToken)
        return InvitationPreview(
            organizationName = tenantName(invitation.tenantId),
            email = invitation.email,
            role = invitation.role.name,
        )
    }

    /**
     * Accepts an invitation for the signed-in account. The account's email must
     * be the invited address — an invitation is for a person, not a bearer link.
     * Returns the tenant to bind the session to.
     */
    @Transactional
    fun accept(
        userId: String,
        rawToken: String,
    ): String {
        val invitation = pendingByToken(rawToken)
        val user =
            users.findById(UUID.fromString(userId)).orElseThrow {
                ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user")
            }
        if (!user.email.equals(invitation.email, ignoreCase = true)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This invitation was sent to a different email address")
        }
        if (memberships.findByUserIdAndTenantId(requireNotNull(user.id), invitation.tenantId) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "You are already a member of this organization")
        }
        memberships.saveAndFlush(
            OrganizerMembership(
                userId = requireNotNull(user.id),
                tenantId = invitation.tenantId,
                role = invitation.role,
                invitedBy = invitation.invitedBy,
            ),
        )
        invitation.status = InvitationStatus.ACCEPTED
        invitations.save(invitation)
        return invitation.tenantId
    }

    @Transactional
    fun changeRole(
        callerId: String,
        tenantId: String,
        targetUserId: UUID,
        request: ChangeRoleRequest,
    ): MemberView {
        val caller = requireCaller(callerId, tenantId)
        val target = requireMembership(targetUserId, tenantId)
        val newRole = parseRole(request.role)
        if ((target.role == OrganizerRole.OWNER || newRole == OrganizerRole.OWNER) && caller.membership.role != OrganizerRole.OWNER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only an owner can grant or revoke ownership")
        }
        if (target.role == OrganizerRole.OWNER && newRole != OrganizerRole.OWNER) {
            requireAnotherOwner(tenantId)
        }
        target.role = newRole
        memberships.save(target)
        val email = users.findById(targetUserId).map { it.email }.orElse("")
        return MemberView(targetUserId, email, newRole.name, target.createdAt)
    }

    @Transactional
    fun removeMember(
        callerId: String,
        tenantId: String,
        targetUserId: UUID,
    ) {
        val caller = requireCaller(callerId, tenantId)
        val target = requireMembership(targetUserId, tenantId)
        if (target.role == OrganizerRole.OWNER) {
            if (caller.membership.role != OrganizerRole.OWNER) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only an owner can remove an owner")
            }
            requireAnotherOwner(tenantId)
        }
        memberships.delete(target)
        // The removed member must lose access on their next request, not at token expiry.
        membershipGate.invalidate(targetUserId.toString(), tenantId)
    }

    private data class Caller(
        val user: OrganizerUser,
        val membership: OrganizerMembership,
    )

    private fun requireCaller(
        callerId: String,
        tenantId: String,
    ): Caller {
        val user =
            users.findById(UUID.fromString(callerId)).orElseThrow {
                ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user")
            }
        val membership =
            memberships.findByUserIdAndTenantId(requireNotNull(user.id), tenantId)
                ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this organization")
        if (membership.role == OrganizerRole.MEMBER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Managing members requires the ADMIN or OWNER role")
        }
        return Caller(user, membership)
    }

    private fun requireMembership(
        userId: UUID,
        tenantId: String,
    ): OrganizerMembership =
        memberships.findByUserIdAndTenantId(userId, tenantId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This person is not a member of the organization")

    private fun requireAnotherOwner(tenantId: String) {
        if (memberships.countByTenantIdAndRole(tenantId, OrganizerRole.OWNER) <= 1) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An organization must keep at least one owner")
        }
    }

    private fun pendingByToken(rawToken: String): MemberInvitation {
        val invitation = invitations.findByTokenHash(Tokens.hash(rawToken))
        if (invitation == null || invitation.status != InvitationStatus.PENDING || invitation.expiresAt.isBefore(Instant.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This invitation is invalid or has expired")
        }
        return invitation
    }

    private fun parseRole(value: String): OrganizerRole =
        runCatching { OrganizerRole.valueOf(value.trim().uppercase()) }.getOrElse {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role: $value")
        }

    private fun tenantName(tenantId: String): String = tenants.findById(UUID.fromString(tenantId)).map { it.name }.orElse("")

    private fun tenantLanguage(tenantId: String): String =
        tenants.findById(UUID.fromString(tenantId)).map { MessageLanguage.forCountry(it.country) }.orElse(MessageLanguage.FRENCH)
}
