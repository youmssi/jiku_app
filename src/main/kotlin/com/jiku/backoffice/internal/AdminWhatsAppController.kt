package com.jiku.backoffice.internal

import com.jiku.messaging.NotificationModuleApi
import com.jiku.messaging.WhatsAppOverrideStatus
import com.jiku.messaging.WhatsAppPricingInfo
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Back-office controls for the WhatsApp guardrails (JIKU-61): pricing (database-
 * backed so Meta's quarterly repricing never needs a redeploy) and the content
 * override, whose every flip is recorded in the admin audit log.
 */
@RestController
@RequestMapping("/admin/whatsapp")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminWhatsAppController(
    private val notificationModuleApi: NotificationModuleApi,
    private val auditService: AdminAuditService,
) {
    @GetMapping("/pricing")
    fun pricing(): List<WhatsAppPricingInfo> = notificationModuleApi.listWhatsAppPricing()

    @PutMapping("/pricing/{category}")
    fun updatePricing(
        @PathVariable category: String,
        @Valid @RequestBody request: UpdatePricingRequest,
    ): WhatsAppPricingInfo {
        val updated = notificationModuleApi.updateWhatsAppPricing(category, request.costUsdMinor)
        auditService.record(
            action = "WHATSAPP_PRICING_UPDATED",
            target = "whatsapp-pricing:$category",
            note = "costUsdMinor=${request.costUsdMinor}",
        )
        return updated
    }

    @GetMapping("/content-override")
    fun contentOverride(): WhatsAppOverrideStatus = notificationModuleApi.whatsAppContentOverrideStatus()

    @PostMapping("/content-override")
    fun setContentOverride(
        @Valid @RequestBody request: SetContentOverrideRequest,
    ): WhatsAppOverrideStatus {
        val status = notificationModuleApi.setWhatsAppContentOverride(request.active, request.reason, currentAdminId())
        auditService.record(
            action = if (request.active) "WHATSAPP_CONTENT_OVERRIDE_ENABLED" else "WHATSAPP_CONTENT_OVERRIDE_DISABLED",
            target = "whatsapp:content-override",
            note = request.reason,
        )
        return status
    }

    private fun currentAdminId(): String = requireNotNull(SecurityContextHolder.getContext().authentication?.name)
}

data class UpdatePricingRequest(
    @field:Min(0) val costUsdMinor: Long,
)

data class SetContentOverrideRequest(
    val active: Boolean,
    @field:NotBlank val reason: String,
)
