package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * The organization's legal identity, used to address invoices (JIKU-69).
 *
 * Restricted to `ORGANIZER_MANAGER` like branding: these details appear on legal
 * documents, so an ordinary member should not be able to change who the company
 * is.
 */
@RestController
@RequestMapping("/legal-identity")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class LegalIdentityController(
    private val service: LegalIdentityService,
) {
    @GetMapping
    fun get(): LegalIdentityResponse = service.get(currentTenantId())

    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateLegalIdentityRequest,
    ): LegalIdentityResponse = service.update(currentTenantId(), request)

    private fun currentTenantId(): UUID {
        val tenantId =
            TenantContext.get()
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context")
        return UUID.fromString(tenantId)
    }
}

@Service
class LegalIdentityService(
    private val tenants: TenantRepository,
) {
    fun get(tenantId: UUID): LegalIdentityResponse = load(tenantId).legalIdentity.toResponse()

    @Transactional
    fun update(
        tenantId: UUID,
        request: UpdateLegalIdentityRequest,
    ): LegalIdentityResponse {
        val tenant = load(tenantId)
        tenant.ensureLegalIdentity().apply {
            legalName = request.legalName?.trim()?.ifBlank { null }
            registrationNumber = request.registrationNumber?.trim()?.ifBlank { null }
            taxIdentifier = request.taxIdentifier?.trim()?.ifBlank { null }
            addressLine = request.addressLine?.trim()?.ifBlank { null }
            city = request.city?.trim()?.ifBlank { null }
            country =
                request.country
                    ?.trim()
                    ?.uppercase()
                    ?.ifBlank { null }
        }
        return tenants.save(tenant).legalIdentity.toResponse()
    }

    private fun load(tenantId: UUID) =
        tenants.findById(tenantId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found")
        }

    private fun TenantLegalIdentity?.toResponse() =
        LegalIdentityResponse(
            legalName = this?.legalName,
            registrationNumber = this?.registrationNumber,
            taxIdentifier = this?.taxIdentifier,
            addressLine = this?.addressLine,
            city = this?.city,
            country = this?.country,
            // Tells the organizer up front whether an invoice can be issued,
            // rather than letting them discover it when one is refused.
            completeForInvoicing = this?.isComplete() ?: false,
        )
}

/**
 * Every field is optional so an organization can fill this in over time; sending a
 * blank clears it. Completeness is reported back rather than enforced here — the
 * refusal belongs at the point an invoice is issued.
 */
data class UpdateLegalIdentityRequest(
    @field:Size(max = 255)
    val legalName: String? = null,
    @field:Size(max = 100)
    val registrationNumber: String? = null,
    @field:Size(max = 100)
    val taxIdentifier: String? = null,
    @field:Size(max = 255)
    val addressLine: String? = null,
    @field:Size(max = 120)
    val city: String? = null,
    @field:Pattern(
        regexp = "^$|^[A-Za-z]{2}$",
        message = "country must be an ISO 3166-1 alpha-2 code, e.g. GN",
    )
    val country: String? = null,
)

data class LegalIdentityResponse(
    val legalName: String?,
    val registrationNumber: String?,
    val taxIdentifier: String?,
    val addressLine: String?,
    val city: String?,
    val country: String?,
    val completeForInvoicing: Boolean,
)
