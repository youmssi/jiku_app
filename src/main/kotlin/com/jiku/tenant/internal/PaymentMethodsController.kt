package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantPaymentMethodsInfo
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

/** Blank fields are cleared. */
data class UpdatePaymentMethodsRequest(
    @field:Size(max = 120) val payeeName: String? = null,
    @field:Size(max = 32) val orangeMoneyNumber: String? = null,
    @field:Size(max = 32) val mtnMomoNumber: String? = null,
    @field:Size(max = 32) val waveNumber: String? = null,
    @field:Size(max = 500)
    @field:Pattern(regexp = "^(https://\\S+)?$", message = "The payment link must be an https:// address")
    val paymentLinkUrl: String? = null,
)

/**
 * The organization's payment methods (JIKU-109): what its clients see when a
 * ticket must be paid. Managers only, like branding.
 */
@RestController
@RequestMapping("/settings/payment-methods")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class PaymentMethodsController(
    private val paymentMethods: PaymentMethodsService,
) {
    @GetMapping
    fun get(): TenantPaymentMethodsInfo = paymentMethods.get(currentTenantId())

    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdatePaymentMethodsRequest,
    ): TenantPaymentMethodsInfo = paymentMethods.update(currentTenantId(), request)

    private fun currentTenantId(): UUID =
        UUID.fromString(TenantContext.get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context"))
}

@Service
class PaymentMethodsService(
    private val tenants: TenantRepository,
) {
    @Transactional(readOnly = true)
    fun get(tenantId: UUID): TenantPaymentMethodsInfo = load(tenantId).paymentMethods.toInfo()

    @Transactional
    fun update(
        tenantId: UUID,
        request: UpdatePaymentMethodsRequest,
    ): TenantPaymentMethodsInfo {
        val methods =
            TenantPaymentMethods(
                payeeName = request.payeeName.clean(),
                orangeMoneyNumber = request.orangeMoneyNumber.clean(),
                mtnMomoNumber = request.mtnMomoNumber.clean(),
                waveNumber = request.waveNumber.clean(),
                paymentLinkUrl = request.paymentLinkUrl.clean(),
            )
        val numbered = listOf(methods.orangeMoneyNumber, methods.mtnMomoNumber, methods.waveNumber).any { it != null }
        if (numbered && methods.payeeName == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Name the payee the client will see on their Mobile Money confirmation")
        }
        val tenant = load(tenantId)
        tenant.paymentMethods = methods.takeIf { it.hasAny() || it.payeeName != null }
        return tenants.save(tenant).paymentMethods.toInfo()
    }

    private fun load(tenantId: UUID): Tenant =
        tenants.findById(tenantId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found") }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
