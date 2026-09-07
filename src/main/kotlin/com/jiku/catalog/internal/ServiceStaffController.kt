package com.jiku.catalog.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Liens du personnel vers la console d'un service (JIKU-88) : l'organisateur les
 * crée (le jeton signé n'est montré qu'à la création), les liste et les révoque.
 * Le personnel n'a pas de compte — il consomme la console par `GET /line/{token}`.
 */
@RestController
@RequestMapping("/services/{serviceId}/staff-links")
@PreAuthorize("hasRole('ORGANIZER')")
class ServiceStaffController(
    private val services: ServiceAdminService,
    private val staff: ServiceStaffRepository,
    private val tokens: DayLineTokenService,
) {
    @GetMapping
    fun list(
        @PathVariable serviceId: UUID,
    ): List<ServiceStaffView> {
        services.get(serviceId)
        return staff.findAllByServiceId(serviceId).map { it.toView() }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable serviceId: UUID,
        @Valid @RequestBody request: ServiceStaffCreateRequest,
    ): ServiceStaffCreatedResponse {
        services.get(serviceId)
        val tenantId = TenantContext.get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant")
        val row = staff.save(ServiceStaff(serviceId = serviceId, label = request.label.trim()))
        return ServiceStaffCreatedResponse(
            id = requireNotNull(row.id),
            label = row.label,
            token = tokens.issue(requireNotNull(row.id), serviceId, tenantId),
            createdAt = row.createdAt,
        )
    }

    @DeleteMapping("/{staffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @PathVariable serviceId: UUID,
        @PathVariable staffId: UUID,
    ) {
        services.get(serviceId)
        val row = staff.findById(staffId).orElseThrow { notFound(staffId) }
        if (row.serviceId != serviceId) throw notFound(staffId)
        row.revoke()
        staff.save(row)
    }

    private fun notFound(staffId: UUID): ResponseStatusException =
        ResponseStatusException(HttpStatus.NOT_FOUND, "Staff link not found: $staffId")
}

private fun ServiceStaff.toView(): ServiceStaffView =
    ServiceStaffView(
        id = requireNotNull(id),
        serviceId = serviceId,
        label = label,
        revoked = revoked,
        createdAt = createdAt,
        revokedAt = revokedAt,
    )
