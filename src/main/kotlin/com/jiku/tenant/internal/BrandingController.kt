package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/branding")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class BrandingController(
    private val brandingService: BrandingService,
) {
    @GetMapping
    fun get(): BrandingResponse = brandingService.getBranding(currentTenantId())

    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateBrandingRequest,
    ): BrandingResponse = brandingService.updateBranding(currentTenantId(), request)

    private fun currentTenantId(): UUID {
        val tenantId =
            TenantContext.get()
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context")
        return UUID.fromString(tenantId)
    }
}
