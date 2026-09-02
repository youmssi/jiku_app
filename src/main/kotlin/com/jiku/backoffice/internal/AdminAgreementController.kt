package com.jiku.backoffice.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** Back-office enterprise agreement management (JIKU-43). */
@RestController
@RequestMapping("/admin/agreements")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminAgreementController(
    private val agreementService: AgreementService,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<AgreementView> = agreementService.list(status, kind, tenantId, page, size).map { it.toView() }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateAgreementRequest,
    ): AgreementView {
        val agreement = agreementService.create(request)
        auditService.record(
            action = "AGREEMENT_CREATED",
            target = "agreement:${agreement.id}",
            note = "tenant:${agreement.tenantId} kind:${agreement.kind} until:${agreement.periodEnd}",
        )
        return agreement.toView()
    }

    @PostMapping("/{id}/renew")
    fun renew(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RenewAgreementRequest,
    ): AgreementView {
        val next = agreementService.renew(id, request)
        auditService.record(
            action = "AGREEMENT_RENEWED",
            target = "agreement:$id",
            note = "next:${next.id} until:${next.periodEnd}",
        )
        return next.toView()
    }

    @PostMapping("/{id}/interrupt")
    fun interrupt(
        @PathVariable id: UUID,
        @Valid @RequestBody request: InterruptAgreementRequest,
    ): AgreementView {
        val agreement = agreementService.interrupt(id, request.reason)
        auditService.record(action = "AGREEMENT_INTERRUPTED", target = "agreement:$id", note = request.reason)
        return agreement.toView()
    }

    private fun Agreement.toView(): AgreementView =
        AgreementView(
            id = requireNotNull(id),
            tenantId = tenantId,
            kind = kind.name,
            periodStart = periodStart,
            periodEnd = periodEnd,
            renewalAt = renewalAt,
            amountMinor = amountMinor,
            currency = currency,
            status = status.name,
            notes = notes,
            interruptedReason = interruptedReason,
            renewedBy = renewedBy,
            createdAt = createdAt,
        )
}

data class CreateAgreementRequest(
    @field:NotNull
    val tenantId: UUID?,
    @field:NotBlank
    val kind: String,
    @field:NotNull
    val periodStart: Instant?,
    @field:NotNull
    val periodEnd: Instant?,
    val renewalAt: Instant?,
    val amountMinor: Long?,
    val currency: String?,
    val notes: String?,
)

data class RenewAgreementRequest(
    @field:NotNull
    val periodEnd: Instant?,
    val renewalAt: Instant?,
    val amountMinor: Long?,
    val currency: String?,
    val notes: String?,
)

data class InterruptAgreementRequest(
    @field:NotBlank(message = "A reason is required")
    val reason: String,
)

data class AgreementView(
    val id: UUID,
    val tenantId: UUID,
    val kind: String,
    val periodStart: Instant,
    val periodEnd: Instant,
    val renewalAt: Instant,
    val amountMinor: Long?,
    val currency: String?,
    val status: String,
    val notes: String?,
    val interruptedReason: String?,
    val renewedBy: UUID?,
    val createdAt: Instant,
)
