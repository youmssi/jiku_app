package com.jiku.tenant

import com.jiku.shared.TenantContext
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val loginRateLimiter: LoginRateLimiter,
) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): AuthResponse = authService.register(request)

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
    ): AuthResponse {
        loginRateLimiter.recordAttempt(httpRequest.remoteAddr)
        return authService.login(request)
    }

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): AuthResponse = authService.refresh(request)

    @GetMapping("/me")
    @PreAuthorize("hasRole('ORGANIZER_ADMIN')")
    fun me(authentication: Authentication): MeResponse =
        MeResponse(
            userId = authentication.name,
            tenantId = TenantContext.get().orEmpty(),
            role =
                authentication.authorities
                    .firstOrNull()
                    ?.authority
                    ?.removePrefix("ROLE_")
                    .orEmpty(),
        )
}
