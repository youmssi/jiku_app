package com.jiku.backoffice.internal

import com.jiku.shared.JwtService
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Platform-admin authentication (JIKU-40). Reuses the application's JWT mechanism
 * with the [ROLE_PLATFORM_ADMIN] role and an empty tenant claim — the security
 * filter treats a blank tenant as "no tenant context", which is exactly what a
 * platform operator must be.
 */
@Service
class AdminAuthService(
    private val admins: PlatformAdminRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {
    @Transactional(readOnly = true)
    fun login(request: AdminLoginRequest): AdminAuthResponse {
        val admin = admins.findByEmail(request.email)
        if (admin == null || !passwordEncoder.matches(request.password, admin.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        }
        return tokensFor(admin)
    }

    @Transactional(readOnly = true)
    fun refresh(request: AdminRefreshRequest): AdminAuthResponse {
        val claims =
            try {
                jwtService.parse(request.refreshToken)
            } catch (ex: RuntimeException) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token", ex)
            }
        if (claims[JwtService.CLAIM_TOKEN_TYPE] != JwtService.TOKEN_TYPE_REFRESH ||
            claims[JwtService.CLAIM_ROLE] != ROLE_PLATFORM_ADMIN
        ) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
        }
        val admin =
            admins.findById(UUID.fromString(claims.subject)).orElseThrow {
                ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token")
            }
        return tokensFor(admin)
    }

    private fun tokensFor(admin: PlatformAdmin): AdminAuthResponse {
        val adminId = requireNotNull(admin.id).toString()
        return AdminAuthResponse(
            accessToken = jwtService.generateAccessToken(adminId, NO_TENANT, ROLE_PLATFORM_ADMIN),
            refreshToken = jwtService.generateRefreshToken(adminId, NO_TENANT, ROLE_PLATFORM_ADMIN),
        )
    }

    companion object {
        const val ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN"
        private const val NO_TENANT = ""
    }
}

data class AdminLoginRequest(
    val email: String,
    val password: String,
)

data class AdminRefreshRequest(
    val refreshToken: String,
)

data class AdminAuthResponse(
    val accessToken: String,
    val refreshToken: String,
)
