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
 * One WhatsApp send's cost (JIKU-61), recorded after a successful delivery so
 * cost can be aggregated by event and the 24h conversation counter can query
 * actual send volume rather than trusting an in-memory count that would reset
 * on restart. Tenant-scoped like [NotificationLog], which this complements —
 * that log is delivery status/audit, this is specifically cost/quota data.
 */
@Entity
@Table(name = "whatsapp_message_cost")
class WhatsAppMessageCost(
    @Column(name = "reference_id")
    val referenceId: UUID?,
    @Column(name = "event_id")
    val eventId: UUID?,
    /** [POOL_PLATFORM] (shared platform number) or [POOL_TENANT] (tenant's own BYO number). */
    @Column(name = "pool", nullable = false)
    val pool: String,
    @Column(name = "category", nullable = false)
    val category: String,
    @Column(name = "cost_usd_minor", nullable = false)
    val costUsdMinor: Long,
    @Column(name = "cost_gnf_minor", nullable = false)
    val costGnfMinor: Long,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    companion object {
        const val POOL_PLATFORM = "PLATFORM"
        const val POOL_TENANT = "TENANT"
    }
}
