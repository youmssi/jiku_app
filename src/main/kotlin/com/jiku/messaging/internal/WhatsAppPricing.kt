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
 * Per-conversation-category WhatsApp Cloud API pricing (JIKU-61), in USD minor
 * units (cents). Deliberately a database row, not a config default — Meta
 * reprices per market on its own schedule, and this must be updatable
 * (`/admin/whatsapp/pricing`) without a redeploy. Not tenant-scoped: pricing is
 * platform-wide, the same for every tenant sending through the same Meta tier.
 */
@Entity
@Table(name = "whatsapp_pricing")
class WhatsAppPricing(
    @Column(name = "category", nullable = false, unique = true)
    val category: String,
    @Column(name = "cost_usd_minor", nullable = false)
    var costUsdMinor: Long,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object {
        const val CATEGORY_UTILITY = "UTILITY"
        const val CATEGORY_MARKETING = "MARKETING"
    }
}
