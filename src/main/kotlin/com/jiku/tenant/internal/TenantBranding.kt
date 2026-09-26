package com.jiku.tenant.internal

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

/**
 * White-label branding a tenant applies to guest-facing pages, emails and tickets.
 * All fields are optional; unset values fall back to Jikū's neutral defaults at
 * read time (see [toBrandingResponse] / [toTenantInfo]).
 */
@Embeddable
class TenantBranding(
    @Column(name = "branding_display_name")
    var displayName: String? = null,
    @Column(name = "branding_logo_url")
    var logoUrl: String? = null,
    @Column(name = "branding_banner_url")
    var bannerUrl: String? = null,
    @Column(name = "branding_primary_color")
    var primaryColor: String? = null,
)
