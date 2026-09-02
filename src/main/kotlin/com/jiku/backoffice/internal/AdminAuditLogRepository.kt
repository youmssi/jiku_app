package com.jiku.backoffice.internal

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AdminAuditLogRepository : JpaRepository<AdminAuditLog, UUID> {
    fun findByAction(
        action: String,
        pageable: Pageable,
    ): Page<AdminAuditLog>
}
