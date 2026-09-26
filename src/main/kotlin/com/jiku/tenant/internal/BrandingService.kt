package com.jiku.tenant.internal

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class BrandingService(
    private val tenants: TenantRepository,
) {
    @Transactional(readOnly = true)
    fun getBranding(tenantId: UUID): BrandingResponse = loadTenant(tenantId).toBrandingResponse()

    @Transactional
    fun updateBranding(
        tenantId: UUID,
        request: UpdateBrandingRequest,
    ): BrandingResponse {
        val tenant = loadTenant(tenantId)
        val branding = tenant.ensureBranding()
        branding.displayName = request.displayName?.takeIf { it.isNotBlank() }
        branding.logoUrl = request.logoUrl?.takeIf { it.isNotBlank() }
        branding.bannerUrl = request.bannerUrl?.takeIf { it.isNotBlank() }
        branding.primaryColor = request.primaryColor?.takeIf { it.isNotBlank() }
        tenants.save(tenant)
        return tenant.toBrandingResponse()
    }

    private fun loadTenant(tenantId: UUID): Tenant =
        tenants.findById(tenantId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")
        }
}
