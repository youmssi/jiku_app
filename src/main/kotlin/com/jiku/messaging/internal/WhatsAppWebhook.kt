package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The WhatsApp Cloud API webhook (JIKU-143): what guests write or tap in the
 * chat. Registered in Meta as `<api>/whatsapp/webhook` with the `messages`
 * field. The GET answers Meta's registration check with the verify token; each
 * POST is authenticated by Meta's signature over the raw body with the app
 * secret. A verified call is always acknowledged, even when nothing in it is
 * ours, so Meta does not retry it.
 */
@RestController
@RequestMapping("/whatsapp/webhook")
class WhatsAppWebhookController(
    private val properties: WhatsAppProperties,
    private val inbound: WhatsAppInboundService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(WhatsAppWebhookController::class.java)

    @GetMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun verify(
        @RequestParam("hub.mode", required = false) mode: String?,
        @RequestParam("hub.verify_token", required = false) token: String?,
        @RequestParam("hub.challenge", required = false) challenge: String?,
    ): String {
        val expected = properties.meta.verifyToken
        if (mode != "subscribe" ||
            expected.isBlank() ||
            token == null ||
            challenge == null ||
            !MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())
        ) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook verification failed")
        }
        return challenge
    }

    @PostMapping
    fun receive(
        @RequestHeader(name = "X-Hub-Signature-256", required = false) signature: String?,
        @RequestBody rawBody: String,
    ) {
        if (!MetaWebhookSignature.verify(properties.meta.appSecret, rawBody, signature)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature")
        }
        for (message in WhatsAppWebhookParser.messages(objectMapper.readTree(rawBody))) {
            try {
                inbound.handle(message)
            } catch (ex: RuntimeException) {
                log.warn("WhatsApp reply from {} could not be handled", message.from, ex)
            }
        }
    }
}

/** Meta signs each webhook call as `sha256=<hex HMAC-SHA256 of the raw body, keyed with the app secret>`. */
object MetaWebhookSignature {
    private const val PREFIX = "sha256="

    fun verify(
        appSecret: String,
        rawBody: String,
        header: String?,
    ): Boolean {
        if (appSecret.isBlank() || header == null || !header.startsWith(PREFIX)) return false
        return MessageDigest.isEqual(sign(appSecret, rawBody).toByteArray(), header.removePrefix(PREFIX).lowercase().toByteArray())
    }

    fun sign(
        appSecret: String,
        rawBody: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(appSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(rawBody.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** What a guest sent: a tapped button's id, or text. [from] is the number, digits only. */
data class InboundWhatsApp(
    val from: String,
    val buttonId: String? = null,
    val text: String? = null,
)

/**
 * Reads the messages out of a webhook call (`entry[].changes[].value.messages[]`).
 * A template's quick-reply button arrives as `button.payload`, a session reply
 * button as `interactive.button_reply.id`, and typed text as `text.body`;
 * everything else (statuses, media, reactions) is not a reply and is skipped.
 */
object WhatsAppWebhookParser {
    fun messages(root: JsonNode): List<InboundWhatsApp> =
        root.path("entry").flatMap { entry ->
            entry.path("changes").flatMap { change ->
                change.path("value").path("messages").mapNotNull(::message)
            }
        }

    private fun message(node: JsonNode): InboundWhatsApp? {
        val from = node.path("from").asString("").filter { it.isDigit() }
        if (from.isEmpty()) return null
        return when (node.path("type").asString("")) {
            "button" ->
                node
                    .path("button")
                    .path("payload")
                    .textOrNull()
                    ?.let { InboundWhatsApp(from, buttonId = it) }
            "interactive" ->
                node
                    .path("interactive")
                    .path("button_reply")
                    .path("id")
                    .textOrNull()
                    ?.let { InboundWhatsApp(from, buttonId = it) }
            "text" ->
                node
                    .path("text")
                    .path("body")
                    .textOrNull()
                    ?.let { InboundWhatsApp(from, text = it) }
            else -> null
        }
    }

    private fun JsonNode.textOrNull(): String? = asString("").takeIf { it.isNotBlank() }
}
