package com.jiku.backoffice.internal

import com.jiku.tenant.AdminVerificationView
import com.jiku.tenant.TenantModuleApi
import com.jiku.tenant.VerificationDocumentLink
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class VerificationRejectRequest(
    @field:NotBlank(message = "Give the organization a reason for the refusal")
    @field:Size(max = 500)
    val reason: String,
)

/**
 * The verification review desk (JIKU-175). Opening a request's documents is
 * itself audited: they are identity papers, and every look at them is logged.
 */
@RestController
@RequestMapping("/admin/verifications")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminVerificationController(
    private val tenantModuleApi: TenantModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "PENDING") status: String,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<AdminVerificationView> = tenantModuleApi.adminListVerifications(status, limit)

    @GetMapping("/{id}/documents")
    fun documents(
        @PathVariable id: UUID,
    ): List<VerificationDocumentLink> {
        val links = tenantModuleApi.adminVerificationDocuments(id)
        auditService.record(action = "VERIFICATION_DOCUMENTS_VIEWED", target = "verification:$id", note = null)
        return links
    }

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
    ): AdminVerificationView {
        val decided = tenantModuleApi.adminDecideVerification(id, approve = true, reason = null, adminId = auditService.currentAdminId())
        auditService.record(action = "VERIFICATION_APPROVED", target = "tenant:${decided.tenantId}", note = "${decided.kind} $id")
        return decided
    }

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @Valid @RequestBody request: VerificationRejectRequest,
    ): AdminVerificationView {
        val decided =
            tenantModuleApi.adminDecideVerification(id, approve = false, reason = request.reason, adminId = auditService.currentAdminId())
        auditService.record(action = "VERIFICATION_REJECTED", target = "tenant:${decided.tenantId}", note = request.reason)
        return decided
    }
}
