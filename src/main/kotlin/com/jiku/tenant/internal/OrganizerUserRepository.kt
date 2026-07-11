package com.jiku.tenant.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrganizerUserRepository : JpaRepository<OrganizerUser, UUID> {
    fun findByEmail(email: String): OrganizerUser?

    fun existsByEmail(email: String): Boolean

    fun countByTenantId(tenantId: String): Long
}
