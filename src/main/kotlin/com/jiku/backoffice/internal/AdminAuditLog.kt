package com.jiku.backoffice.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Immutable record of a platform-admin action (JIKU-40). Every mutation performed
 * through the back-office writes one entry; the log is append-only by convention
 * (no update or delete path exists in the application).
 */
@Entity
@Table(name = "admin_audit_log")
class AdminAuditLog(
    @Column(name = "admin_id", nullable = false, updatable = false)
    val adminId: UUID,
    @Column(name = "action", nullable = false, updatable = false)
    val action: String,
    @Column(name = "target", nullable = false, updatable = false)
    val target: String,
    @Column(name = "note", updatable = false)
    val note: String?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
