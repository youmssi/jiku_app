package com.jiku.tenant.internal

import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A tenant space owned by an organizer. This is the tenant *definition*, not
 * tenant-scoped data, so it is intentionally not a [com.jiku.shared.BaseTenantEntity].
 */
@Entity
@Table(name = "tenant")
class Tenant(
    @Column(name = "name", nullable = false)
    val name: String,
    @Column(name = "contact_email", nullable = false)
    val contactEmail: String,
    /** ISO 3166-1 alpha-2; fixed at creation (JIKU-107). */
    @Column(name = "country", nullable = false, updatable = false)
    val country: String,
    /** ISO 4217 currency of every price this organization sets or pays. */
    @Column(name = "currency", nullable = false, updatable = false)
    val currency: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: TenantStatus = TenantStatus.ACTIVE,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    /**
     * Identifiant public de l'organisation (profil découvert à
     * https://…/o/{username}). Facultatif ; unique quand renseigné, insensible à
     * la casse une fois normalisé.
     */
    @Column(name = "username", unique = true)
    var username: String? = null

    // Nullable because Hibernate maps an all-null embeddable to a null reference.
    @Embedded
    var branding: TenantBranding? = null

    /** Supplied only by organizations that need a compliant invoice (JIKU-69). */
    @Embedded
    var legalIdentity: TenantLegalIdentity? = null

    fun ensureBranding(): TenantBranding = branding ?: TenantBranding().also { branding = it }

    fun ensureLegalIdentity(): TenantLegalIdentity = legalIdentity ?: TenantLegalIdentity().also { legalIdentity = it }
}
