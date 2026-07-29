package com.jiku.tenant.internal

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    // Optional organization name (JIKU-48): when present the user's first
    // organization is created in the same transaction — the original one-step
    // registration. Omitted, the account starts with no organization and the
    // frontend routes to onboarding.
    val name: String? = null,
    // The person's display name (JIKU-54). Optional so existing API clients
    // keep working; blank is treated as absent.
    @field:Size(max = 255) val fullName: String? = null,
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank @field:Size(min = 8, message = "Password must be at least 8 characters") val password: String,
)

data class LoginRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

data class RefreshRequest(
    @field:NotBlank val refreshToken: String,
)

data class GoogleLoginRequest(
    @field:NotBlank val idToken: String,
)

data class CreateOrgRequest(
    @field:NotBlank val name: String,
)

data class ForgotPasswordRequest(
    @field:Email @field:NotBlank val email: String,
)

data class ResetPasswordRequest(
    @field:NotBlank val token: String,
    @field:NotBlank @field:Size(min = 8, message = "Password must be at least 8 characters") val password: String,
)

data class VerifyEmailRequest(
    @field:NotBlank val token: String,
)

data class SwitchOrgRequest(
    @field:NotBlank val tenantId: String,
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
)

data class MembershipView(
    val tenantId: String,
    val tenantName: String,
    val role: String,
)

data class MeResponse(
    val userId: String,
    val email: String,
    /** Null when the account never provided a name; the UI shows the email. */
    val fullName: String?,
    val tenantId: String,
    val role: String,
    val memberships: List<MembershipView>,
)
