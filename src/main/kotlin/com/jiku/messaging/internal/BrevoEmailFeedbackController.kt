package com.jiku.messaging.internal

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
import java.security.MessageDigest

/**
 * Receives Brevo's transactional webhooks (JIKU-275). Configure the webhook in
 * Brevo as `<api>/notifications/email-feedback/brevo` with the events hard
 * bounce, soft bounce, invalid email and spam, and bearer authentication with
 * the token set as BREVO_WEBHOOK_TOKEN. Verified events feed the same
 * [FeedbackEvent] pipeline as the other providers.
 */
@RestController
@RequestMapping("/notifications/email-feedback/brevo")
class BrevoEmailFeedbackController(
    private val reputationService: SenderReputationService,
    private val properties: EmailReputationProperties,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receive(
        @RequestHeader(name = "Authorization", required = false) authorization: String?,
        @RequestBody rawBody: String,
    ) {
        val token = properties.brevoWebhookToken
        if (token.isNullOrBlank() || !BrevoWebhookAuth.matches(token, authorization)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook token")
        }
        val events = BrevoFeedbackMapper.map(objectMapper.readTree(rawBody))
        if (events.isNotEmpty()) {
            reputationService.recordFeedback(events)
        }
    }
}

/** Constant-time check of `Authorization: Bearer <token>`. */
object BrevoWebhookAuth {
    private const val PREFIX = "Bearer "

    fun matches(
        token: String,
        authorization: String?,
    ): Boolean {
        if (authorization == null || !authorization.startsWith(PREFIX)) return false
        val presented = authorization.removePrefix(PREFIX).trim()
        return MessageDigest.isEqual(token.toByteArray(), presented.toByteArray())
    }
}

/**
 * Maps a Brevo webhook payload (one event object, or an array when Brevo
 * batches them) to [FeedbackEvent]s. An invalid address counts as a hard
 * bounce; a spam report counts as a complaint. Other events (delivered,
 * opened, blocked…) are ignored: blocked means Brevo refused to send, not that
 * the recipient bounced.
 */
object BrevoFeedbackMapper {
    fun map(root: JsonNode): List<FeedbackEvent> = if (root.isArray) root.flatMap { event(it) } else event(root)

    private fun event(node: JsonNode): List<FeedbackEvent> {
        val type =
            when (node.get("event")?.asString()) {
                "hard_bounce", "invalid_email" -> EmailFeedback.TYPE_HARD_BOUNCE
                "soft_bounce" -> EmailFeedback.TYPE_SOFT_BOUNCE
                "spam", "complaint" -> EmailFeedback.TYPE_COMPLAINT
                else -> return emptyList()
            }
        val email = node.get("email")?.asString()
        return if (email.isNullOrBlank()) emptyList() else listOf(FeedbackEvent(email, type))
    }
}
