package com.jiku.messaging.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * A tenant's own messaging provider for one channel (JIKU-44). When present it
 * overrides the platform transport for that tenant's sends; deleting the row
 * falls back to the platform default. [credentials] holds the provider's
 * credential JSON encrypted by [CredentialsCipher] — it is never exposed raw
 * through any API and never logged.
 */
@Entity
@Table(name = "tenant_provider_settings")
class TenantProviderSettings(
    @Column(name = "channel", nullable = false)
    val channel: String,
    @Column(name = "provider", nullable = false)
    var provider: String,
    @Column(name = "credentials", nullable = false)
    var credentials: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    companion object {
        const val CHANNEL_EMAIL = "EMAIL"
        const val CHANNEL_WHATSAPP = "WHATSAPP"
        const val PROVIDER_RESEND = "RESEND"
        const val PROVIDER_META_CLOUD = "META_CLOUD"
    }
}

interface TenantProviderSettingsRepository : JpaRepository<TenantProviderSettings, UUID> {
    fun findByChannel(channel: String): TenantProviderSettings?
}
