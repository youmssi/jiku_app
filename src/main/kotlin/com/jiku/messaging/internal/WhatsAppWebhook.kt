package com.jiku.messaging.internal

import com.jiku.shared.TenantTransaction
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
 * ours, so Meta does not retry it. A message to an organization's own number
 * (ADR 105) is handled for that organization and answered from its number.
 * What Meta reports about templates, numbers and accounts (JIKU-209) goes to
 * [WhatsAppHealthService].
 */
@RestController
@RequestMapping("/whatsapp/webhook")
class WhatsAppWebhookController(
    private val properties: WhatsAppProperties,
    private val inbound: WhatsAppInboundService,
    private val cards: OpenCardInboundService,
    private val numbers: WhatsAppBusinessNumberRepository,
    private val tenantTransaction: TenantTransaction,
    private val objectMapper: ObjectMapper,
    private val health: WhatsAppHealthService,
) {
    private val log = LoggerFactory.getLogger(WhatsAppWebhookController::class.java)

    @GetMapping(produces = [MediaType.TEXT_PLAIN_VALUE])
    fun verifySubscription(
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
    fun receiveMessages(
        @RequestHeader(name = "X-Hub-Signature-256", required = false) signature: String?,
        @RequestBody rawBody: String,
    ) {
        if (!MetaWebhookSignature.verify(properties.meta.appSecret, rawBody, signature)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature")
        }
        val root = objectMapper.readTree(rawBody)
        for (event in WhatsAppAccountEventParser.events(root)) {
            try {
                apply(event)
            } catch (ex: RuntimeException) {
                log.warn("WhatsApp account event {} could not be handled", event, ex)
            }
        }
        for (message in WhatsAppWebhookParser.messages(root)) {
            try {
                if (cards.isCardsNumber(message.businessNumberId)) {
                    cards.handle(message)
                    continue
                }
                val tenantId = message.businessNumberId?.let { numbers.findById(it).orElse(null) }?.tenantId
                if (tenantId == null) {
                    inbound.handle(message)
                } else {
                    tenantTransaction.run(tenantId) { inbound.handle(message, tenantId) }
                }
            } catch (ex: RuntimeException) {
                log.warn("WhatsApp reply from {} could not be handled", message.from, ex)
            }
        }
    }

    private fun apply(event: WhatsAppAccountEvent) {
        when (event) {
            is WhatsAppAccountEvent.TemplateStatus ->
                health.onTemplateStatus(event.wabaId, event.name, event.language, event.status, event.reason)
            is WhatsAppAccountEvent.TemplateQuality -> health.onTemplateQuality(event.wabaId, event.name, event.language, event.quality)
            is WhatsAppAccountEvent.TemplateCategory ->
                health.onTemplateCategory(event.wabaId, event.name, event.language, event.category)
            is WhatsAppAccountEvent.NumberQuality ->
                health.onPhoneNumberQuality(event.wabaId, event.displayPhoneNumber, event.event, event.limit)
            is WhatsAppAccountEvent.Account -> health.onAccountUpdate(event.wabaId, event.event, event.detail)
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

/**
 * What a guest sent: a tapped button's id, or text. [from] is the number,
 * digits only; [businessNumberId] is the Meta id of the number it was sent to.
 */
data class InboundWhatsApp(
    val from: String,
    val buttonId: String? = null,
    val text: String? = null,
    val businessNumberId: String? = null,
    /** The name the sender shows in WhatsApp (`contacts[].profile.name`), when Meta gives it. */
    val profileName: String? = null,
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
                val value = change.path("value")
                val businessNumberId = value.path("metadata").path("phone_number_id").textOrNull()
                val names =
                    value
                        .path("contacts")
                        .mapNotNull { contact ->
                            val id = contact.path("wa_id").asString("").filter { it.isDigit() }
                            contact
                                .path("profile")
                                .path("name")
                                .textOrNull()
                                ?.let { id to it.trim() }
                        }.toMap()
                value.path("messages").mapNotNull { node ->
                    message(node)?.let { it.copy(businessNumberId = businessNumberId, profileName = names[it.from]) }
                }
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

/** What Meta reports about a WhatsApp Business Account [wabaId], its templates and numbers (JIKU-209). */
sealed interface WhatsAppAccountEvent {
    val wabaId: String

    data class TemplateStatus(
        override val wabaId: String,
        val name: String,
        val language: String,
        val status: String,
        val reason: String?,
    ) : WhatsAppAccountEvent

    data class TemplateQuality(
        override val wabaId: String,
        val name: String,
        val language: String,
        val quality: String,
    ) : WhatsAppAccountEvent

    data class TemplateCategory(
        override val wabaId: String,
        val name: String,
        val language: String,
        val category: String,
    ) : WhatsAppAccountEvent

    data class NumberQuality(
        override val wabaId: String,
        val displayPhoneNumber: String?,
        val event: String,
        val limit: String?,
    ) : WhatsAppAccountEvent

    data class Account(
        override val wabaId: String,
        val event: String,
        val detail: String?,
    ) : WhatsAppAccountEvent
}

/**
 * Reads account events out of a webhook call: `entry[].id` is the WhatsApp
 * Business Account and each `changes[].field` names the event. Unknown fields,
 * and events missing what they need, are skipped.
 */
object WhatsAppAccountEventParser {
    fun events(root: JsonNode): List<WhatsAppAccountEvent> =
        root.path("entry").flatMap { entry ->
            val wabaId = entry.path("id").text()
            if (wabaId == null) {
                emptyList()
            } else {
                entry.path("changes").mapNotNull { change -> event(wabaId, change.path("field").asString(""), change.path("value")) }
            }
        }

    private fun event(
        wabaId: String,
        field: String,
        value: JsonNode,
    ): WhatsAppAccountEvent? {
        val name = value.path("message_template_name").text()
        val language = value.path("message_template_language").text()
        return when (field) {
            "message_template_status_update" ->
                template(name, language, value.path("event").text()) { n, l, status ->
                    WhatsAppAccountEvent.TemplateStatus(wabaId, n, l, status, value.path("reason").text())
                }
            "message_template_quality_update" ->
                template(name, language, value.path("new_quality_score").text()) { n, l, quality ->
                    WhatsAppAccountEvent.TemplateQuality(wabaId, n, l, quality)
                }
            "template_category_update" ->
                template(name, language, value.path("new_category").text()) { n, l, category ->
                    WhatsAppAccountEvent.TemplateCategory(wabaId, n, l, category)
                }
            "phone_number_quality_update" ->
                value.path("event").text()?.let {
                    WhatsAppAccountEvent.NumberQuality(
                        wabaId,
                        value.path("display_phone_number").text(),
                        it,
                        value.path("current_limit").text() ?: value.path("max_daily_conversations_per_business").text(),
                    )
                }
            "account_update" ->
                value.path("event").text()?.let {
                    WhatsAppAccountEvent.Account(wabaId, it, accountDetail(value))
                }
            else -> null
        }
    }

    private fun template(
        name: String?,
        language: String?,
        value: String?,
        build: (String, String, String) -> WhatsAppAccountEvent,
    ): WhatsAppAccountEvent? = if (name == null || language == null || value == null) null else build(name, language, value)

    private fun accountDetail(value: JsonNode): String? =
        listOfNotNull(
            value.path("violation_info").path("violation_type").text(),
            value.path("ban_info").path("waba_ban_state").text(),
            value.path("restriction_info").takeIf { it.isArray && !it.isEmpty }?.joinToString(", ") {
                it.path("restriction_type").asString("")
            },
        ).joinToString("; ").ifBlank { null }

    private fun JsonNode.text(): String? = if (isValueNode) asString("").trim().takeIf { it.isNotEmpty() } else null
}
