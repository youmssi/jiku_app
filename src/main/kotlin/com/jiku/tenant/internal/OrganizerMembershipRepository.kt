package com.jiku.tenant.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrganizerMembershipRepository : JpaRepository<OrganizerMembership, UUID> {
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID): List<OrganizerMembership>

    fun findByUserIdAndTenantId(
        userId: UUID,
        tenantId: String,
    ): OrganizerMembership?

    fun countByTenantId(tenantId: String): Long

    fun findByTenantIdOrderByCreatedAtAsc(tenantId: String): List<OrganizerMembership>

    fun countByTenantIdAndRole(
        tenantId: String,
        role: OrganizerRole,
    ): Long
}
