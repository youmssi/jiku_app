package com.jiku.messaging.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A bounce or complaint reported by the email provider. Deliberately NOT
 * tenant-scoped: reputation is a platform-wide concern (all tenants share the
 * sending infrastructure), so the monitor and the undeliverable check read across
 * tenants. [tenantId] is the attributed tenant for per-tenant reporting.
 */
@Entity
@Table(name = "email_feedback")
class EmailFeedback(
    @Column(name = "recipient", nullable = false)
    val recipient: String,
    @Column(name = "feedback_type", nullable = false)
    val feedbackType: String,
    @Column(name = "tenant_id")
    val tenantId: String?,
    @Column(name = "reference_id")
    val referenceId: UUID?,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    companion object {
        const val TYPE_HARD_BOUNCE = "HARD_BOUNCE"
        const val TYPE_SOFT_BOUNCE = "SOFT_BOUNCE"
        const val TYPE_COMPLAINT = "COMPLAINT"

        val BOUNCE_TYPES = setOf(TYPE_HARD_BOUNCE, TYPE_SOFT_BOUNCE)
    }
}
