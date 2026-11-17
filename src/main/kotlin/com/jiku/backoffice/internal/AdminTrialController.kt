package com.jiku.backoffice.internal

import com.jiku.money.AdminTrialPage
import com.jiku.money.AdminTrialStats
import com.jiku.money.AdminTrialView
import com.jiku.money.BillingModuleApi
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Back-office follow-up of guests lent to events (JIKU-42): where every trial
 * stands and the way to end one early. Trials are started by gifts since
 * JIKU-319, never directly.
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

data class EndTrialRequest(
    @field:NotBlank(message = "A reason is required")
    val reason: String,
)
