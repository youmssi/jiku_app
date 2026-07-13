package com.jiku.notification.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Org settings for messaging providers (JIKU-44): the organization owner plugs
 * in their own Resend key and WhatsApp Cloud API credentials, reads back only
 * masked values, reverts to the platform default, and verifies the setup with
 * a one-click test send. Tenant scoping comes from the Hibernate tenant filter.
 */
@RestController
@RequestMapping("/settings/providers")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class ProviderSettingsController(
    private val service: TenantProviderSettingsService,
) {
    @GetMapping
    fun overview(): ProviderSettingsResponse = service.overview()

    @PutMapping("/email")
    fun updateEmail(
        @Valid @RequestBody request: UpdateEmailProviderRequest,
    ): ProviderSettingsResponse = service.updateEmail(request)

    @PutMapping("/whatsapp")
    fun updateWhatsApp(
        @Valid @RequestBody request: UpdateWhatsAppProviderRequest,
    ): ProviderSettingsResponse = service.updateWhatsApp(request)

    @DeleteMapping("/{channel}")
    fun remove(
        @PathVariable channel: String,
    ): ProviderSettingsResponse = service.remove(validChannel(channel))

    @PostMapping("/{channel}/test")
    fun testSend(
        @PathVariable channel: String,
        @Valid @RequestBody request: TestSendRequest,
    ): TestSendResponse = service.testSend(validChannel(channel), request.recipient.trim())

    private fun validChannel(channel: String): String =
        when (channel.uppercase()) {
            TenantProviderSettings.CHANNEL_EMAIL -> TenantProviderSettings.CHANNEL_EMAIL
            TenantProviderSettings.CHANNEL_WHATSAPP -> TenantProviderSettings.CHANNEL_WHATSAPP
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown channel: $channel")
        }
}
