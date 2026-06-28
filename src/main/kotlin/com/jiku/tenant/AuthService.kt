package com.jiku.tenant

import com.jiku.shared.JwtService
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class AuthService(
    private val users: OrganizerUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
) {
    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        if (users.existsByEmail(request.email)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists")
        }
        val user =
            OrganizerUser(
                tenantId = UUID.randomUUID().toString(),
                email = request.email,
                passwordHash = requireNotNull(passwordEncoder.encode(request.password)),
            )
        users.save(user)
        return tokensFor(user)
    }

    @Transactional(readOnly = true)
    fun login(request: LoginRequest): AuthResponse {
        val user = users.findByEmail(request.email)
        if (user == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        }
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
        return tokensFor(user)
    }

    private fun tokensFor(user: OrganizerUser): AuthResponse {
        val userId = requireNotNull(user.id).toString()
        return AuthResponse(
            accessToken = jwtService.generateAccessToken(userId, user.tenantId, user.role.name),
            refreshToken = jwtService.generateRefreshToken(userId, user.tenantId, user.role.name),
        )
    }
}
