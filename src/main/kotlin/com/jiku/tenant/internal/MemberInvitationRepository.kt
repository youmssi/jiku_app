package com.jiku.tenant.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MemberInvitationRepository : JpaRepository<MemberInvitation, UUID> {
    fun findByTokenHash(tokenHash: String): MemberInvitation?

    fun findByTenantIdAndEmailIgnoreCaseAndStatus(
        tenantId: String,
        email: String,
        status: InvitationStatus,
    ): MemberInvitation?

    fun findByTenantIdAndStatusOrderByCreatedAtDesc(
        tenantId: String,
        status: InvitationStatus,
    ): List<MemberInvitation>
}
