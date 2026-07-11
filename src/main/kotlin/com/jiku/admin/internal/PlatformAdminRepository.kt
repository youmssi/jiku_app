package com.jiku.admin.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlatformAdminRepository : JpaRepository<PlatformAdmin, UUID> {
    fun findByEmail(email: String): PlatformAdmin?

    fun existsByEmail(email: String): Boolean
}
