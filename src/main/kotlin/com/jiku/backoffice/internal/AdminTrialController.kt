package com.jiku.backoffice.internal

import com.jiku.money.AdminTrialPage
import com.jiku.money.AdminTrialStats
import com.jiku.money.AdminTrialView
import com.jiku.money.BillingModuleApi
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

/**
 * Back-office trial management (JIKU-42): grant a prospect a time-boxed paid
 * allowance, end it early, and see where every trial stands.
 */
@RestController
@RequestMapping("/admin/trials")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminTrialController(
    private val billingModuleApi: BillingModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): AdminTrialPage = billingModuleApi.adminListTrials(status, tenantId, page, size)

    @GetMapping("/stats")
    fun stats(): AdminTrialStats = billingModuleApi.adminTrialStats()

    @PostMapping
    fun grant(
        @Valid @RequestBody request: GrantTrialRequest,
    ): AdminTrialView {
        val view =
            billingModuleApi.adminGrantTrial(
                tenantId = requireNotNull(request.tenantId),
                eventId = requireNotNull(request.eventId),
                tier = request.tier,
                expiresAt = requireNotNull(request.expiresAt),
            )
        auditService.record(
            action = "TRIAL_GRANTED",
            target = "trial:${view.id}",
            note = "event:${view.eventId} tier:${view.tier} until:${view.expiresAt}",
        )
        return view
    }

    @PostMapping("/{id}/end")
    fun end(
        @PathVariable id: UUID,
        @Valid @RequestBody request: EndTrialRequest,
    ): AdminTrialView {
        val view = billingModuleApi.adminEndTrial(id, request.reason)
        auditService.record(action = "TRIAL_ENDED", target = "trial:$id", note = request.reason)
        return view
    }
}

data class GrantTrialRequest(
    @field:NotNull
    val tenantId: UUID?,
    @field:NotNull
    val eventId: UUID?,
    @field:NotBlank
    val tier: String,
    @field:NotNull
    val expiresAt: Instant?,
)

data class EndTrialRequest(
    @field:NotBlank(message = "A reason is required")
    val reason: String,
)
