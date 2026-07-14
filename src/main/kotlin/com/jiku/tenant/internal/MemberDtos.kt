package com.jiku.tenant.internal

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

data class MemberView(
    val userId: UUID,
    val email: String,
    val role: String,
    val joinedAt: Instant,
)

data class InvitationView(
    val id: UUID,
    val email: String,
    val role: String,
    val expiresAt: Instant,
    val createdAt: Instant,
)

data class InviteMemberRequest(
    @field:Email @field:NotBlank val email: String,
    @field:NotBlank val role: String,
)

data class ChangeRoleRequest(
    @field:NotBlank val role: String,
)

data class AcceptInvitationRequest(
    @field:NotBlank val token: String,
)

data class InvitationPreview(
    val organizationName: String,
    val email: String,
    val role: String,
)
