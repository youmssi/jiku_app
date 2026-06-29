package com.jiku.notification.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * An audit record of a single delivery attempt. Tenant-scoped. [referenceId] links
 * back to the originating entity (e.g. an invitation) for support queries.
 */
@Entity
@Table(name = "notification_log")
class NotificationLog(
    @Column(name = "reference_id")
    val referenceId: UUID?,
    @Column(name = "channel", nullable = false)
    val channel: String,
    @Column(name = "recipient", nullable = false)
    val recipient: String,
    @Column(name = "status", nullable = false)
    val status: String,
    @Column(name = "attempt", nullable = false)
    val attempt: Int,
    @Column(name = "error")
    val error: String? = null,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}
