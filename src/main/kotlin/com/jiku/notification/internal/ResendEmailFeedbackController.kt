package com.jiku.notification.internal

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Receives Resend's bounce/complaint webhooks (JIKU-28B). Resend delivers events
 * through Svix, so authentication is the Svix signature over the raw body, not
 * the shared-secret header the provider-agnostic endpoint uses. Verified events
 * are normalized into the same [FeedbackEvent] pipeline.
 *
 * Configure the endpoint in Resend as `<api>/notifications/email-feedback/resend`
 * with the events `email.bounced` and `email.complained`, and set the signing
 * secret (whsec_…) as RESEND_WEBHOOK_SECRET.
 */
@RestController
@RequestMapping("/notifications/email-feedback/resend")
class ResendEmailFeedbackController(
    private val reputationService: SenderReputationService,
    private val properties: EmailReputationProperties,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receive(
        @RequestHeader(name = "svix-id", required = false) svixId: String?,
        @RequestHeader(name = "svix-timestamp", required = false) svixTimestamp: String?,
        @RequestHeader(name = "svix-signature", required = false) svixSignature: String?,
        @RequestBody rawBody: String,
    ) {
        val secret = properties.resendWebhookSecret
        if (secret.isNullOrBlank() ||
            !SvixSignatureVerifier.verify(secret, svixId, svixTimestamp, svixSignature, rawBody)
        ) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature")
        }
        val events = ResendFeedbackMapper.map(objectMapper.readTree(rawBody))
        if (events.isNotEmpty()) {
            reputationService.recordFeedback(events)
        }
    }
}

/**
 * Maps one Resend webhook payload (`{"type": "email.bounced", "data": {…}}`)
 * to normalized [FeedbackEvent]s. Bounce hardness follows the `data.bounce.type`
 * classification (`Permanent` → hard, `Transient` → soft, defaulting to hard so
 * an unknown classification never under-counts against the reputation
 * thresholds). Event types other than bounces and complaints are ignored.
 */
object ResendFeedbackMapper {
    fun map(root: JsonNode): List<FeedbackEvent> {
        val feedbackType =
            when (root.get("type")?.asString()) {
                "email.bounced" -> bounceType(root.get("data"))
                "email.complained" -> EmailFeedback.TYPE_COMPLAINT
                else -> return emptyList()
            }
        return recipients(root.get("data")).map { FeedbackEvent(it, feedbackType) }
    }

    private fun bounceType(data: JsonNode?): String =
        when (
            data
                ?.get("bounce")
                ?.get("type")
                ?.asString()
                ?.lowercase()
        ) {
            "transient" -> EmailFeedback.TYPE_SOFT_BOUNCE
            else -> EmailFeedback.TYPE_HARD_BOUNCE
        }

    private fun recipients(data: JsonNode?): List<String> {
        val to = data?.get("to") ?: return emptyList()
        return if (to.isArray) {
            to.mapNotNull { it.asString() }.filter { it.isNotBlank() }
        } else {
            listOfNotNull(to.asString()).filter { it.isNotBlank() }
        }
    }
}
