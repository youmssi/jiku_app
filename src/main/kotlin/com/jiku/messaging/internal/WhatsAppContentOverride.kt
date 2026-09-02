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
 * Single-row platform-wide switch (JIKU-61): when active, WhatsApp bodies
 * classified MARKETING are allowed to send instead of being blocked by
 * [WhatsAppContentGuard]. Not tenant-scoped — the content guard protects the
 * platform's Meta account standing, which is shared. Flipping it is always
 * recorded in the admin audit log by the controller that calls
 * [com.jiku.messaging.NotificationModuleApi.setWhatsAppContentOverride].
 */
@Entity
@Table(name = "whatsapp_content_override")
class WhatsAppContentOverride {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "active", nullable = false)
    var active: Boolean = false

    @Column(name = "reason")
    var reason: String? = null

    @Column(name = "activated_by")
    var activatedBy: String? = null

    @Column(name = "activated_at")
    var activatedAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
