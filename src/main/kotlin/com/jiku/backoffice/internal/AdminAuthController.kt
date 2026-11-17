package com.jiku.backoffice.internal

import com.jiku.mfa.MfaEnrollmentStart
import com.jiku.mfa.MfaStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform-admin login and token refresh (JIKU-40), and the second factor's code
 * step and first setup (JIKU-317), opened by the step token a password returns.
 * These are the only public part of the admin surface; everything else under
 * /admin requires the PLATFORM_ADMIN role.
 */
@RestController
@RequestMapping("/admin/auth")
class AdminAuthController(
    private val authService: AdminAuthService,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: AdminLoginRequest,
    ): AdminAuthResponse = authService.login(request)

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: AdminRefreshRequest,
    ): AdminAuthResponse = authService.refresh(request)

    @PostMapping("/mfa/verify")
    fun verifyMfa(
        @Valid @RequestBody request: AdminMfaVerifyRequest,
    ): AdminAuthResponse = authService.verifyMfa(request)

    @PostMapping("/mfa/setup")
    fun startSetup(
        @Valid @RequestBody request: AdminMfaSetupRequest,
    ): MfaEnrollmentStart = authService.startSetup(request)

    @PostMapping("/mfa/setup/confirm")
    fun confirmSetup(
        @Valid @RequestBody request: AdminMfaConfirmRequest,
    ): AdminMfaSetupResponse = authService.confirmSetup(request)
}

data class AdminMfaCodeRequest(
    @field:NotBlank @field:Size(max = 12) val code: String,
)

data class AdminBackupCodesView(
    val backupCodes: List<String>,
)

/** The signed-in administrator's own second factor (JIKU-317). */
@RestController
@RequestMapping("/admin/me/mfa")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminMfaController(
    private val authService: AdminAuthService,
) {
    @GetMapping
    fun status(authentication: Authentication): MfaStatus = authService.status(UUID.fromString(authentication.name))

    @PostMapping("/backup-codes")
    fun renewBackupCodes(
        authentication: Authentication,
        @Valid @RequestBody request: AdminMfaCodeRequest,
    ): AdminBackupCodesView = AdminBackupCodesView(authService.renewBackupCodes(UUID.fromString(authentication.name), request.code))
}
