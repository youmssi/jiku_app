package com.jiku.tenant.internal

import com.jiku.shared.JwtService
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
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {
    /**
     * Creates a tenant and its first organizer in one transaction. If either insert
     * violates a uniqueness constraint the whole transaction rolls back, so a failed
     * registration never leaves an orphaned tenant behind.
     */
    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        try {
            val tenant = tenants.saveAndFlush(Tenant(name = request.name, contactEmail = request.email))
            val user =
                users.saveAndFlush(
                    OrganizerUser(
                        tenantId = requireNotNull(tenant.id).toString(),
                        email = request.email,
                        passwordHash = requireNotNull(passwordEncoder.encode(request.password)),
                    ),
                )
            return tokensFor(user)
        } catch (ex: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists", ex)
        }
    }

    @Transactional(readOnly = true)
    fun login(request: LoginRequest): AuthResponse {
        val user = users.findByEmail(request.email)
        if (user == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        }
        rejectSuspendedTenant(user)
        return tokensFor(user)
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
        rejectSuspendedTenant(user)
        return tokensFor(user)
    }

    /** Suspension (JIKU-40) blocks token issuance entirely, with an explicit reason. */
    private fun rejectSuspendedTenant(user: OrganizerUser) {
        val tenant = tenants.findById(UUID.fromString(user.tenantId)).orElse(null)
        if (tenant?.status == TenantStatus.SUSPENDED) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been suspended. Contact support.")
        }
    }

    private fun tokensFor(user: OrganizerUser): AuthResponse {
        val userId = requireNotNull(user.id).toString()
        return AuthResponse(
            accessToken = jwtService.generateAccessToken(userId, user.tenantId, user.role.name),
            refreshToken = jwtService.generateRefreshToken(userId, user.tenantId, user.role.name),
        )
    }
}
