package com.jiku.messaging.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Meta WhatsApp Cloud API adapter (JIKU-43). Sends through the official Graph
 * API, either as a session message (plain text, reply buttons or an image; these
 * reach a recipient inside the 24-hour customer-service window and test numbers)
 * or, when a template name is configured, as an approved template whose single
 * body parameter carries the rendered text — the pattern for business-initiated
 * sends outside the 24-hour window.
 *
 * A message with buttons uses [buttonsTemplateName] (a template with as many
 * quick-reply buttons, each given its id as payload), and one with an image uses
 * [imageTemplateName] (a template with an image header) (JIKU-143).
 *
 * Instances are built per credential set: the platform's own (env-configured)
 * or a tenant's (organizer-provided in the org settings), both through
 * [MetaCloudWhatsAppSender.build].
 *
 * With a [gate] and the [wabaId] of the account, a template Meta paused,
 * disabled or moved to marketing is not tried, and Meta's refusals that will
 * not clear within seconds (paused template, blocked account, rate limits)
 * raise [WhatsAppUnavailableException] so the message waits or goes by SMS
 * (JIKU-209).
 *
 * With a [templatePrefix], a message that names its dedicated template
 * (JIKU-210) goes out as that template, `<prefix><kind>` in the message's
 * language, with its own parameters, QR header and reply buttons. A
 * [WhatsAppMessage.session] message is always sent as a session message.
 */
class MetaCloudWhatsAppSender internal constructor(
    private val client: RestClient,
    private val phoneNumberId: String,
    private val templateName: String?,
    private val templateLanguage: String,
    private val buttonsTemplateName: String? = null,
    private val imageTemplateName: String? = null,
    private val wabaId: String? = null,
    private val gate: WhatsAppTemplateGate? = null,
    private val templatePrefix: String? = null,
) : WhatsAppSender {
    private data class Chosen(
        val name: String,
        val language: String,
    )

    override fun send(message: WhatsAppMessage): String? {
        val chosen = templateFor(message)
        if (chosen != null && wabaId != null) gate?.assertUsable(wabaId, chosen.name, chosen.language)
        val body = payload(message)
        try {
            val response =
                client
                    .post()
                    .uri("/{phoneNumberId}/messages", phoneNumberId)
                    .body(body)
                    .retrieve()
                    .body(String::class.java)
            return response?.let { MESSAGE_ID.find(it)?.groupValues?.get(1) }
        } catch (e: HttpStatusCodeException) {
            val code = errorCode(e.responseBodyAsString)
            if (code != null) {
                gate?.onSendRefused(wabaId, chosen?.name, chosen?.language ?: templateLanguage, code)
                if (code in WAIT_CODES) {
                    throw WhatsAppUnavailableException("WhatsApp refused the message to ${message.to} for now (Meta error $code)")
                }
            }
            throw WhatsAppDeliveryException(
                "WhatsApp Cloud API rejected message to ${message.to} (${e.statusCode}): ${e.responseBodyAsString}",
                e,
            )
        } catch (e: ResourceAccessException) {
            throw WhatsAppDeliveryException("WhatsApp Cloud API unreachable for ${message.to}", e)
        }
    }

    private fun dedicated(message: WhatsAppMessage): WhatsAppTemplateCall? =
        message.template?.takeIf { !message.session && !templatePrefix.isNullOrBlank() }

    /** The approved template [payload] uses for [message], or null for a session message. */
    private fun templateFor(message: WhatsAppMessage): Chosen? {
        dedicated(message)?.let { return Chosen(templatePrefix + it.kind.key, it.language) }
        if (message.session) return null
        val name =
            when {
                message.buttons.isNotEmpty() -> buttonsTemplateName
                message.imageUrl != null -> imageTemplateName
                else -> templateName
            }
        return name?.takeIf { it.isNotBlank() }?.let { Chosen(it, templateLanguage) }
    }

    /** The dedicated template: its body parameters, the QR header and a payload per reply button. */
    private fun dedicatedPayload(
        message: WhatsAppMessage,
        call: WhatsAppTemplateCall,
    ): Map<String, Any> {
        val header = message.imageUrl?.let { headerComponent(mapOf("type" to "image", "image" to mapOf("link" to it))) }
        val body = mapOf("type" to "body", "parameters" to call.parameters.map { mapOf("type" to "text", "text" to it) })
        val buttons =
            message.buttons.mapIndexed { index, button ->
                mapOf(
                    "type" to "button",
                    "sub_type" to "quick_reply",
                    "index" to index.toString(),
                    "parameters" to listOf(mapOf("type" to "payload", "payload" to button.id)),
                )
            }
        return session(
            message.to,
            "template",
            mapOf(
                "name" to templatePrefix + call.kind.key,
                "language" to mapOf("code" to call.language),
                "components" to listOfNotNull(header, body) + buttons,
            ),
        )
    }

    private fun errorCode(body: String): Int? =
        ERROR_CODE
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun payload(message: WhatsAppMessage): Map<String, Any> {
        dedicated(message)?.let { return dedicatedPayload(message, it) }
        val bodyParameter = mapOf("type" to "body", "parameters" to listOf(mapOf("type" to "text", "text" to message.body)))
        val header = message.imageUrl?.let { mapOf("type" to "image", "image" to mapOf("link" to it)) }
        return when {
            message.buttons.isNotEmpty() && !message.session && !buttonsTemplateName.isNullOrBlank() ->
                template(
                    message.to,
                    buttonsTemplateName,
                    listOfNotNull(header?.let(::headerComponent), bodyParameter) +
                        message.buttons.mapIndexed { index, button ->
                            mapOf(
                                "type" to "button",
                                "sub_type" to "quick_reply",
                                "index" to index.toString(),
                                "parameters" to listOf(mapOf("type" to "payload", "payload" to button.id)),
                            )
                        },
                )

            message.buttons.isNotEmpty() ->
                session(
                    message.to,
                    "interactive",
                    mapOf(
                        "type" to "button",
                        "body" to mapOf("text" to message.body),
                        "action" to
                            mapOf(
                                "buttons" to
                                    message.buttons.map { mapOf("type" to "reply", "reply" to mapOf("id" to it.id, "title" to it.title)) },
                            ),
                    ) + listOfNotNull(header?.let { "header" to it }),
                )

            header != null && !message.session && !imageTemplateName.isNullOrBlank() ->
                template(message.to, imageTemplateName, listOf(headerComponent(header), bodyParameter))

            message.imageUrl != null -> session(message.to, "image", mapOf("link" to message.imageUrl, "caption" to message.body))

            !message.session && !templateName.isNullOrBlank() -> template(message.to, templateName, listOf(bodyParameter))

            else -> session(message.to, "text", mapOf("body" to message.body))
        }
    }

    private fun headerComponent(image: Map<String, Any>): Map<String, Any> = mapOf("type" to "header", "parameters" to listOf(image))

    private fun template(
        to: String,
        name: String,
        components: List<Map<String, Any>>,
    ): Map<String, Any> =
        session(
            to,
            "template",
            mapOf("name" to name, "language" to mapOf("code" to templateLanguage), "components" to components),
        )

    private fun session(
        to: String,
        type: String,
        content: Map<String, Any>,
    ): Map<String, Any> = mapOf("messaging_product" to "whatsapp", "to" to to, "type" to type, type to content)

    companion object {
        private val MESSAGE_ID = Regex("\"id\"\\s*:\\s*\"(wamid\\.[^\"]+)\"")
        private val ERROR_CODE = Regex("\"code\"\\s*:\\s*(\\d+)")

        /**
         * Meta refusals that clear with time, not with a retry a second later:
         * template paused (132015) or disabled (132016), account locked (131031)
         * or blocked for policy (368), and the throughput, spam, per-recipient
         * and account rate limits (130429, 131048, 131056, 80007).
         */
        private val WAIT_CODES = setOf(132015, 132016, 131031, 368, 130429, 131048, 131056, 80007)

        internal fun build(
            builder: RestClient.Builder,
            accessToken: String,
            phoneNumberId: String,
            baseUrl: String,
            templateName: String?,
            templateLanguage: String,
            buttonsTemplateName: String? = null,
            imageTemplateName: String? = null,
            wabaId: String? = null,
            gate: WhatsAppTemplateGate? = null,
            templatePrefix: String? = null,
        ): MetaCloudWhatsAppSender {
            check(accessToken.isNotBlank()) { "WhatsApp Cloud API access token is not set" }
            check(phoneNumberId.isNotBlank()) { "WhatsApp Cloud API phone number id is not set" }
            val factory = SimpleClientHttpRequestFactory()
            factory.setConnectTimeout(Duration.ofSeconds(5))
            factory.setReadTimeout(Duration.ofSeconds(15))
            val client =
                builder
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
            return MetaCloudWhatsAppSender(
                client,
                phoneNumberId,
                templateName,
                templateLanguage,
                buttonsTemplateName,
                imageTemplateName,
                wabaId,
                gate,
                templatePrefix,
            )
        }
    }
}
