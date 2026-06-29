package com.jiku.notification.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationLogRepository : JpaRepository<NotificationLog, UUID> {
    fun findByReferenceId(referenceId: UUID): List<NotificationLog>

    fun countByStatus(status: String): Long
}
