package com.jiku.backoffice.internal

import com.jiku.mfa.MfaEnrollmentStart
import com.jiku.mfa.MfaModuleApi
import com.jiku.mfa.MfaPurpose
import com.jiku.mfa.MfaStatus
import com.jiku.mfa.MfaSubject
import com.jiku.shared.CodedRefusal
import com.jiku.shared.JwtService
import com.jiku.shared.SecurityNotice
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.ApplicationEventPublisher
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
 *
 * The desk sees every organization, so it takes a second factor (JIKU-317): a
 * password alone yields a step token, either for the code or, while
 * `ADMIN_MFA_REQUIRED` holds and the factor is not set up yet, for setting it up.
 */
@Service
class AdminAuthService(
    private val admins: PlatformAdminRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val mfa: MfaModuleApi,
    private val events: ApplicationEventPublisher,
) {
    private val unknownAccountHash: String by lazy { requireNotNull(passwordEncoder.encode(UUID.randomUUID().toString())) }

    @Transactional(readOnly = true)
    fun login(request: AdminLoginRequest): AdminAuthResponse {
        val admin = admins.findByEmail(request.email)
        // An unknown address still costs one hash check, so the response time
        // does not tell which addresses have an account.
        val hash = admin?.passwordHash ?: unknownAccountHash
        if (!passwordEncoder.matches(request.password, hash) || admin == null) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password")
        }
        val subject = subjectOf(admin)
        if (mfa.status(subject).enabled) {
            if (!mfa.isTrustedDevice(subject, request.trustedDeviceToken)) {
                throw CodedRefusal(
                    HttpStatus.UNAUTHORIZED,
                    CodedRefusal.MFA_REQUIRED,
                    "Enter the code from your authentication app",
                    mapOf("challengeToken" to mfa.challenge(subject, MfaPurpose.VERIFY)),
                )
            }
        } else if (mfa.adminRequired()) {
            throw CodedRefusal(
                HttpStatus.UNAUTHORIZED,
                CodedRefusal.MFA_SETUP_REQUIRED,
                "Set up an authentication app to use the desk",
                mapOf("challengeToken" to mfa.challenge(subject, MfaPurpose.SETUP)),
            )
        }
        return tokensFor(admin)
    }

    @Transactional
    fun verifyMfa(request: AdminMfaVerifyRequest): AdminAuthResponse {
        val admin = adminFor(request.challengeToken, MfaPurpose.VERIFY)
        val subject = subjectOf(admin)
        mfa.verify(subject, request.code, request.backupCode)
        val response = tokensFor(admin)
        return if (request.trustDevice) response.copy(trustedDeviceToken = mfa.trustDevice(subject)) else response
    }

    @Transactional
    fun startSetup(request: AdminMfaSetupRequest): MfaEnrollmentStart {
        val admin = adminFor(request.challengeToken, MfaPurpose.SETUP)
        return mfa.startEnrollment(subjectOf(admin), admin.email)
    }

    @Transactional
    fun confirmSetup(request: AdminMfaConfirmRequest): AdminMfaSetupResponse {
        val admin = adminFor(request.challengeToken, MfaPurpose.SETUP)
        val codes = mfa.confirmEnrollment(subjectOf(admin), request.code.trim())
        notify(admin, SecurityNotice.KIND_MFA_ENABLED)
        val tokens = tokensFor(admin)
        return AdminMfaSetupResponse(tokens.accessToken, tokens.refreshToken, codes)
    }

    fun status(adminId: UUID): MfaStatus = mfa.status(MfaSubject(MfaSubject.ADMIN, adminId))

    @Transactional
    fun renewBackupCodes(
        adminId: UUID,
        code: String,
    ): List<String> {
        val admin = admins.findById(adminId).orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown administrator") }
        val codes = mfa.renewBackupCodes(subjectOf(admin), code)
        notify(admin, SecurityNotice.KIND_BACKUP_CODES_RENEWED)
        return codes
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
        // A session opened before the factor became mandatory signs in again,
        // and so goes through setting it up.
        if (mfa.adminRequired() && !mfa.status(subjectOf(admin)).enabled) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again to set up your authentication app")
        }
        return tokensFor(admin)
    }

    private fun adminFor(
        challengeToken: String,
        purpose: MfaPurpose,
    ): PlatformAdmin {
        val adminId = mfa.readChallenge(challengeToken, MfaSubject.ADMIN, purpose)
        return admins.findById(adminId).orElseThrow {
            CodedRefusal(HttpStatus.UNAUTHORIZED, CodedRefusal.MFA_INVALID, "This sign-in has expired. Start again.")
        }
    }

    private fun notify(
        admin: PlatformAdmin,
        kind: String,
    ) {
        events.publishEvent(SecurityNotice(kind = kind, email = admin.email))
    }

    private fun subjectOf(admin: PlatformAdmin) = MfaSubject(MfaSubject.ADMIN, requireNotNull(admin.id))

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
    /** Skips the code step on a device remembered after an earlier one (JIKU-317). */
    @field:Size(max = 2048) val trustedDeviceToken: String? = null,
)

data class AdminRefreshRequest(
    val refreshToken: String,
)

data class AdminAuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val trustedDeviceToken: String? = null,
)

data class AdminMfaVerifyRequest(
    @field:NotBlank @field:Size(max = 2048) val challengeToken: String,
    @field:Size(max = 12) val code: String? = null,
    @field:Size(max = 32) val backupCode: String? = null,
    val trustDevice: Boolean = false,
)

data class AdminMfaSetupRequest(
    @field:NotBlank @field:Size(max = 2048) val challengeToken: String,
)

data class AdminMfaConfirmRequest(
    @field:NotBlank @field:Size(max = 2048) val challengeToken: String,
    @field:NotBlank @field:Size(max = 12) val code: String,
)

/** The session opened by a first setup, with the backup codes shown once. */
data class AdminMfaSetupResponse(
    val accessToken: String,
    val refreshToken: String,
    val backupCodes: List<String>,
)
