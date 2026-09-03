package com.jiku.backoffice.internal

import com.jiku.tenant.TenantDirectoryPage
import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantModuleApi
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Back-office tenant directory (JIKU-40): search, suspend, reactivate. Reads and
 * mutations go through the tenant module's exposed API; suspension takes effect
 * on the target tenant's very next request.
 */
@RestController
@RequestMapping("/admin/tenants")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminTenantController(
    private val tenantModuleApi: TenantModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): TenantDirectoryPage = tenantModuleApi.searchTenants(query, page, size)

    @PostMapping("/{id}/suspend")
    fun suspend(
        @PathVariable id: UUID,
        @Valid @RequestBody request: TenantStatusChangeRequest,
    ): TenantInfo = changeStatus(id, suspended = true, action = "TENANT_SUSPENDED", note = request.note)

    @PostMapping("/{id}/reactivate")
    fun reactivate(
        @PathVariable id: UUID,
        @Valid @RequestBody request: TenantStatusChangeRequest,
    ): TenantInfo = changeStatus(id, suspended = false, action = "TENANT_REACTIVATED", note = request.note)

    private fun changeStatus(
        id: UUID,
        suspended: Boolean,
        action: String,
        note: String,
    ): TenantInfo {
        val updated =
            tenantModuleApi.setTenantSuspended(id, suspended)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")
        auditService.record(action = action, target = "tenant:$id", note = note)
        return updated
    }
}

data class TenantStatusChangeRequest(
    @field:NotBlank(message = "A note explaining the action is required")
    val note: String,
)
