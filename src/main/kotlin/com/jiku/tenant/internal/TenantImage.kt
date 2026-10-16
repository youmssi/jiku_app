package com.jiku.tenant.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Limits and public address of the organization's logo and banner (JIKU-259). */
@ConfigurationProperties(prefix = "tenant.images")
data class TenantImageProperties(
    /** This API's public URL: the photos' links, shown on public pages and in emails. */
    val apiPublicUrl: String = "http://localhost:8080",
    /** The largest photo accepted; the browser resizes it well under this before upload. */
    val maxBytes: Int = 1_048_576,
    val logoMinSize: Int = 128,
    val bannerMinWidth: Int = 600,
    val bannerMinHeight: Int = 150,
)

/** Which of the organization's photos: its square logo or its wide banner. */
enum class TenantImageKind {
    LOGO,
    BANNER,
}

/** One of the organization's uploaded photos. */
@Entity
@Table(name = "tenant_image")
class TenantImage(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    val kind: TenantImageKind,
    @Column(name = "content", nullable = false)
    var content: ByteArray,
    @Column(name = "content_type", nullable = false, length = 32)
    var contentType: String,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) : BaseTenantEntity()

/** A photo as a public page reads it: before any tenant is bound. */
interface PublicTenantImage {
    val content: ByteArray
    val contentType: String
}

interface TenantImageRepository : JpaRepository<TenantImage, UUID> {
    fun findByKind(kind: TenantImageKind): TenantImage?

    /** Native, so the tenant filter does not apply: a guest's page or an email client has no tenant. */
    @Query(
        nativeQuery = true,
        value =
            "SELECT i.content AS content, i.content_type AS contentType FROM tenant_image i " +
                "WHERE i.tenant_id = :tenantId AND i.kind = :kind",
    )
    fun findPublic(
        @Param("tenantId") tenantId: String,
        @Param("kind") kind: String,
    ): PublicTenantImage?
}
