package com.jiku.backoffice.internal

import com.jiku.money.AdminGiftPage
import com.jiku.money.AdminGiftView
import com.jiku.money.BillingModuleApi
import com.jiku.money.OfferGiftCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
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

/**
 * Back-office gifts (JIKU-315): offer an organization guests for an event,
 * months of a services plan or months of the organizer pack, take back one not
 * yet activated, and see them all. Every action lands in the audit log.
 */
@RestController
@RequestMapping("/admin/gifts")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminGiftController(
    private val billingModuleApi: BillingModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): AdminGiftPage = billingModuleApi.adminListGifts(status, tenantId, page, size)

    @PostMapping
    fun offer(
        @Valid @RequestBody request: OfferGiftRequest,
    ): AdminGiftView {
        val view =
            billingModuleApi.adminOfferGift(
                OfferGiftCommand(
                    tenantId = requireNotNull(request.tenantId),
                    kind = request.kind,
                    guests = request.guests,
                    days = request.days,
                    plan = request.plan,
                    months = request.months,
                    eventId = request.eventId,
                    message = request.message,
                    claimBy = request.claimBy,
                    applyNow = request.applyNow,
                    createdBy = "admin:${auditService.currentAdminId()}",
                ),
            )
        auditService.record(
            action = if (request.applyNow) "GIFT_APPLIED" else "GIFT_OFFERED",
            target = "gift:${view.id}",
            note =
                "tenant:${view.tenantId} kind:${view.kind} guests:${view.guests} days:${view.days} " +
                    "plan:${view.plan} months:${view.months}",
        )
        return view
    }

    @PostMapping("/{id}/revoke")
    fun revoke(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RevokeGiftRequest,
    ): AdminGiftView {
        val view = billingModuleApi.adminRevokeGift(id, request.reason)
        auditService.record(action = "GIFT_REVOKED", target = "gift:$id", note = request.reason)
        return view
    }
}

data class OfferGiftRequest(
    @field:NotNull
    val tenantId: UUID?,
    @field:NotBlank
    val kind: String,
    val guests: Long? = null,
    val days: Int? = null,
    val plan: String? = null,
    val months: Int? = null,
    val eventId: UUID? = null,
    @field:Size(max = 500)
    val message: String? = null,
    val claimBy: Instant? = null,
    val applyNow: Boolean = false,
)

data class RevokeGiftRequest(
    @field:NotBlank(message = "A reason is required")
    @field:Size(max = 500)
    val reason: String,
)
