package com.jiku.messaging.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Receives email bounce/complaint webhooks. The payload is the provider-agnostic
 * canonical shape; a provider-specific parser maps a real provider's format to it
 * (the chosen provider — JIKU-16 — drops in later). Authenticated by a shared
 * secret header rather than a user session, since the caller is the provider.
 */
@RestController
@RequestMapping("/notifications/email-feedback")
class EmailFeedbackController(
    private val reputationService: SenderReputationService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receive(
        @RequestHeader(name = "X-Webhook-Secret", required = false) secret: String?,
        @Valid @RequestBody request: EmailFeedbackWebhookRequest,
    ) {
        if (!reputationService.verifyWebhookSecret(secret)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook secret")
        }
        reputationService.recordFeedback(request.events.map { FeedbackEvent(it.recipient, it.type) })
    }
}

data class EmailFeedbackWebhookRequest(
    @field:Valid
    val events: List<EmailFeedbackEventDto> = emptyList(),
)

data class EmailFeedbackEventDto(
    @field:NotBlank
    val recipient: String,
    @field:NotBlank
    val type: String,
)
