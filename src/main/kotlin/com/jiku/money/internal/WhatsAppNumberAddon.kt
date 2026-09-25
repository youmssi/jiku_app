package com.jiku.money.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * The "own WhatsApp number" add-on a tenant paid for (ADR 105), one row per
 * tenant; each paid period pushes [expiresAt] further.
 */
@Entity
@Table(name = "whatsapp_number_addon")
class WhatsAppNumberAddon(
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

interface WhatsAppNumberAddonRepository : JpaRepository<WhatsAppNumberAddon, UUID> {
    /** The current tenant's add-on, if it ever bought one. */
    fun findFirstByOrderByExpiresAtDesc(): WhatsAppNumberAddon?
}
