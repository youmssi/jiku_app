package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
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
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
    private val accountTokenService: AccountTokenService,
) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody request: RegisterRequest,
    ): AuthResponse = authService.register(request)

    // Brute-force protection lives in the shared rate-limiting filter (the
    // `login` policy in application.yaml), not in this controller.
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
    ): AuthResponse = authService.login(request)

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): AuthResponse = authService.refresh(request)

    @PostMapping("/google")
    fun google(
        @Valid @RequestBody request: GoogleLoginRequest,
    ): AuthResponse = authService.googleSignIn(request)

    // Any signed-in account (bound to an organization or not) can rebind its
    // session; membership in the target organization is checked in the service.
    @PostMapping("/switch-org")
    @PreAuthorize("hasRole('USER')")
    fun switchOrg(
        authentication: Authentication,
        @Valid @RequestBody request: SwitchOrgRequest,
    ): AuthResponse = authService.switchOrg(authentication.name, request)

    @GetMapping("/me")
    @PreAuthorize("hasRole('USER')")
    fun me(authentication: Authentication): MeResponse = authService.me(authentication.name, TenantContext.get().orEmpty())

    // Public and deliberately mute about whether the address has an account.
    @PostMapping("/forgot-password")
    fun forgotPassword(
        @Valid @RequestBody request: ForgotPasswordRequest,
    ) = accountTokenService.requestPasswordReset(request.email)

    @PostMapping("/reset-password")
    fun resetPassword(
        @Valid @RequestBody request: ResetPasswordRequest,
    ) = accountTokenService.resetPassword(request.token, request.password)

    @PostMapping("/verify-email")
    fun verifyEmail(
        @Valid @RequestBody request: VerifyEmailRequest,
    ) = accountTokenService.verifyEmail(request.token)

    @PostMapping("/verify-email/resend")
    @PreAuthorize("hasRole('USER')")
    fun resendVerification(authentication: Authentication) = accountTokenService.resendEmailVerification(authentication.name)
}
