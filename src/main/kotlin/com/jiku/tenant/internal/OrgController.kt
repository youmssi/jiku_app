package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantModuleApi
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class UpdateOrgUsernameRequest(
    @field:Size(min = 3, max = 32, message = "Username must be between 3 and 32 characters")
    @field:Pattern(
        regexp = "^[a-z0-9-]+$",
        message = "Username may only contain lowercase letters, numbers and hyphens",
    )
    val username: String,
)

/**
 * Organization creation and identity (JIKU-48). Any signed-in account can create
 * an organization and becomes its OWNER; the response tokens are already bound to
 * the new organization so the frontend lands straight in it. The public username
 * backs the discoverable profile at /o/{username} and is set by admins and owners.
 */
@RestController
@RequestMapping("/orgs")
class OrgController(
    private val authService: AuthService,
    private val tenantModuleApi: TenantModuleApi,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('USER')")
    fun create(
        authentication: Authentication,
        @Valid @RequestBody request: CreateOrgRequest,
    ): AuthResponse = authService.createOrganization(authentication.name, request)

    @GetMapping("/profile")
    @PreAuthorize("hasRole('ORGANIZER')")
    fun profile(): TenantInfo {
        val tenantId =
            TenantContext.get()
                ?: throw org.springframework.web.server
                    .ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant")
        return tenantModuleApi.findTenant(UUID.fromString(tenantId))
            ?: throw org.springframework.web.server
                .ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found")
    }

    @PutMapping("/username")
    @PreAuthorize("hasRole('ORGANIZER_MANAGER')")
    fun updateUsername(
        @Valid @RequestBody request: UpdateOrgUsernameRequest,
    ): TenantInfo {
        val tenantId =
            TenantContext.get() ?: throw org.springframework.web.server
                .ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant")
        return tenantModuleApi.updateUsername(UUID.fromString(tenantId), request.username)
    }
}
