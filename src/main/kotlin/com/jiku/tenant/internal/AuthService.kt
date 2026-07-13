package com.jiku.tenant.internal

import com.jiku.shared.JwtService
import com.jiku.shared.security.TokenRoles
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AuthService(
    private val tenants: TenantRepository,
    private val users: OrganizerUserRepository,
    private val memberships: OrganizerMembershipRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {
    /**
     * Creates a user account (JIKU-48). With an organization [RegisterRequest.name]
     * the user's first organization and OWNER membership are created in the same
     * transaction (the original one-step registration, kept for compatibility);
     * without it the account starts with no organization. If any insert violates a
     * uniqueness constraint the whole transaction rolls back, so a failed
     * registration never leaves an orphaned tenant behind.
     */
    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        val orgName = request.name?.trim()
        if (orgName != null && orgName.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization name must not be blank")
        }
        try {
            val user =
                users.saveAndFlush(
                    OrganizerUser(
                        email = request.email,
                        passwordHash = requireNotNull(passwordEncoder.encode(request.password)),
                    ),
                )
            val membership = orgName?.let { createOrganizationFor(user, it) }
            return tokensFor(user, membership)
        } catch (ex: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists", ex)
        }
    }

    /** Creates an organization owned by the caller and rebinds the session to it. */
    @Transactional
    fun createOrganization(
        userId: String,
        request: CreateOrgRequest,
    ): AuthResponse {
        val user = requireUser(userId)
        return tokensFor(user, createOrganizationFor(user, request.name.trim()))
    }

    @Transactional(readOnly = true)
    fun login(request: LoginRequest): AuthResponse {
        val user = users.findByEmail(request.email)
        if (user == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        }
        return tokensFor(user, bindableMembership(user))
    }

    @Transactional(readOnly = true)
    fun refresh(request: RefreshRequest): AuthResponse {
        val claims =
            try {
                jwtService.parse(request.refreshToken)
            } catch (ex: RuntimeException) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", ex)
            }
        if (claims[JwtService.CLAIM_TOKEN_TYPE] != JwtService.TOKEN_TYPE_REFRESH) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
        }
        val user =
            users.findById(UUID.fromString(claims.subject)).orElseThrow {
                ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
            }
        // Re-derive the binding rather than trusting the old claims: the membership
        // (or its role) may have changed since the refresh token was issued. A
        // vanished or suspended active organization falls back to the next one.
        val tokenTenantId = claims[JwtService.CLAIM_TENANT_ID] as? String
        val current =
            tokenTenantId
                ?.takeIf { it.isNotBlank() && !isSuspended(it) }
                ?.let { memberships.findByUserIdAndTenantId(requireNotNull(user.id), it) }
        return tokensFor(user, current ?: bindableMembership(user))
    }

    /**
     * Rebinds the session to another organization the user is a member of (JIKU-48).
     */
    @Transactional(readOnly = true)
    fun switchOrg(
        userId: String,
        request: SwitchOrgRequest,
    ): AuthResponse {
        val user = requireUser(userId)
        val membership =
            memberships.findByUserIdAndTenantId(requireNotNull(user.id), request.tenantId)
                ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this organization")
        rejectSuspended(request.tenantId)
        return tokensFor(user, membership)
    }

    @Transactional(readOnly = true)
    fun me(
        userId: String,
        activeTenantId: String,
    ): MeResponse {
        val user = requireUser(userId)
        val all = memberships.findByUserIdOrderByCreatedAtDesc(requireNotNull(user.id))
        val tenantNames =
            tenants
                .findAllById(all.map { UUID.fromString(it.tenantId) })
                .associate { it.id.toString() to it.name }
        val activeRole =
            all.firstOrNull { it.tenantId == activeTenantId }?.role?.tokenRole ?: TokenRoles.USER
        return MeResponse(
            userId = userId,
            email = user.email,
            tenantId = activeTenantId,
            role = activeRole,
            memberships =
                all.map {
                    MembershipView(
                        tenantId = it.tenantId,
                        tenantName = tenantNames[it.tenantId].orEmpty(),
                        role = it.role.name,
                    )
                },
        )
    }

    private fun createOrganizationFor(
        user: OrganizerUser,
        name: String,
    ): OrganizerMembership {
        val tenant = tenants.saveAndFlush(Tenant(name = name, contactEmail = user.email))
        return memberships.saveAndFlush(
            OrganizerMembership(
                userId = requireNotNull(user.id),
                tenantId = requireNotNull(tenant.id).toString(),
                role = OrganizerRole.OWNER,
            ),
        )
    }

    /**
     * The organization a fresh session binds to: the most recent membership whose
     * organization is not suspended. No membership at all binds nothing (the
     * frontend routes to onboarding); memberships that are all suspended reject
     * the login outright (JIKU-40 semantics).
     */
    private fun bindableMembership(user: OrganizerUser): OrganizerMembership? {
        val all = memberships.findByUserIdOrderByCreatedAtDesc(requireNotNull(user.id))
        if (all.isEmpty()) return null
        return all.firstOrNull { !isSuspended(it.tenantId) }
            ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been suspended. Contact support.")
    }

    private fun rejectSuspended(tenantId: String) {
        if (isSuspended(tenantId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been suspended. Contact support.")
        }
    }

    private fun isSuspended(tenantId: String): Boolean =
        tenants.findById(UUID.fromString(tenantId)).orElse(null)?.status == TenantStatus.SUSPENDED

    private fun requireUser(userId: String): OrganizerUser =
        users.findById(UUID.fromString(userId)).orElseThrow {
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user")
        }

    private fun tokensFor(
        user: OrganizerUser,
        membership: OrganizerMembership?,
    ): AuthResponse {
        val userId = requireNotNull(user.id).toString()
        val tenantId = membership?.tenantId.orEmpty()
        val role = membership?.role?.tokenRole ?: TokenRoles.USER
        return AuthResponse(
            accessToken = jwtService.generateAccessToken(userId, tenantId, role),
            refreshToken = jwtService.generateRefreshToken(userId, tenantId, role),
        )
    }
}
