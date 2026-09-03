package com.jiku.backoffice.internal

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AdminAuditService(
    private val entries: AdminAuditLogRepository,
) {
    /**
     * Records an admin action attributed to the authenticated platform admin.
     * REQUIRED propagation keeps the entry inside the caller's transaction, so an
     * action and its audit record commit or roll back together.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    fun record(
        action: String,
        target: String,
        note: String?,
    ) {
        entries.save(
            AdminAuditLog(
                adminId = currentAdminId(),
                action = action,
                target = target,
                note = note,
            ),
        )
    }

    private fun currentAdminId(): UUID {
        val principal = SecurityContextHolder.getContext().authentication?.name
        return requireNotNull(principal?.let { runCatching { UUID.fromString(it) }.getOrNull() }) {
            "Audit records require an authenticated platform admin"
        }
    }
}
