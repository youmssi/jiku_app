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
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Receives useSend's bounce/complaint webhooks (ADR 107). Configure the endpoint
 * in useSend as `<api>/notifications/email-feedback/usesend` with the events
 * `email.bounced` and `email.complained`, and set its signing secret as
 * USESEND_WEBHOOK_SECRET. Verified events feed the same [FeedbackEvent] pipeline
 * as the other providers.
 */
@RestController
@RequestMapping("/notifications/email-feedback/usesend")
class UseSendEmailFeedbackController(
    private val reputationService: SenderReputationService,
    private val properties: EmailReputationProperties,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun receive(
        @RequestHeader(name = "X-UseSend-Signature", required = false) signature: String?,
        @RequestHeader(name = "X-UseSend-Timestamp", required = false) timestamp: String?,
        @RequestBody rawBody: String,
    ) {
        val secret = properties.usesendWebhookSecret
        if (secret.isNullOrBlank() || !UseSendSignatureVerifier.verify(secret, timestamp, signature, rawBody)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature")
        }
        val events = UseSendFeedbackMapper.map(objectMapper.readTree(rawBody))
        if (events.isNotEmpty()) {
            reputationService.recordFeedback(events)
        }
    }
}

/**
 * useSend signs `{timestamp}.{raw body}` with HMAC-SHA256 keyed by the secret
 * as given, hex-encoded and prefixed with `v1=`. The timestamp is in
 * milliseconds; one outside the tolerance window is rejected to prevent replay.
 */
object UseSendSignatureVerifier {
    private const val VERSION_PREFIX = "v1="
    private const val TOLERANCE_MILLIS = 300_000L

    fun verify(
        secret: String,
        timestamp: String?,
        signature: String?,
        payload: String,
        now: Instant = Instant.now(),
    ): Boolean {
        if (timestamp.isNullOrBlank() || signature.isNullOrBlank()) return false
        val sentAt = timestamp.toLongOrNull() ?: return false
        if (kotlin.math.abs(now.toEpochMilli() - sentAt) > TOLERANCE_MILLIS) return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected =
            VERSION_PREFIX +
                HexFormat.of().formatHex(mac.doFinal("$timestamp.$payload".toByteArray(Charsets.UTF_8)))
        return MessageDigest.isEqual(expected.toByteArray(), signature.trim().toByteArray())
    }
}

/**
 * Maps one useSend webhook payload (`{"type": "email.bounced", "data": {…}}`) to
 * normalized [FeedbackEvent]s. Only `Transient` bounces count as soft: a
 * `Permanent` or `Undetermined` bounce counts as hard so an unclear
 * classification never under-counts against the reputation thresholds. Other
 * event types, including the dashboard's test event, are ignored.
 */
object UseSendFeedbackMapper {
    fun map(root: JsonNode): List<FeedbackEvent> {
        val data = root.get("data")
        val feedbackType =
            when (root.get("type")?.asString()) {
                "email.bounced" -> bounceType(data)
                "email.complained" -> EmailFeedback.TYPE_COMPLAINT
                else -> return emptyList()
            }
        return recipients(data).map { FeedbackEvent(it, feedbackType) }
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
