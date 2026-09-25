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
 */
class MetaCloudWhatsAppSender internal constructor(
    private val client: RestClient,
    private val phoneNumberId: String,
    private val templateName: String?,
    private val templateLanguage: String,
    private val buttonsTemplateName: String? = null,
    private val imageTemplateName: String? = null,
) : WhatsAppSender {
    override fun send(message: WhatsAppMessage) {
        val body = payload(message)
        try {
            client
                .post()
                .uri("/{phoneNumberId}/messages", phoneNumberId)
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpStatusCodeException) {
            throw WhatsAppDeliveryException(
                "WhatsApp Cloud API rejected message to ${message.to} (${e.statusCode}): ${e.responseBodyAsString}",
                e,
            )
        } catch (e: ResourceAccessException) {
            throw WhatsAppDeliveryException("WhatsApp Cloud API unreachable for ${message.to}", e)
        }
    }

    private fun payload(message: WhatsAppMessage): Map<String, Any> {
        val bodyParameter = mapOf("type" to "body", "parameters" to listOf(mapOf("type" to "text", "text" to message.body)))
        val header = message.imageUrl?.let { mapOf("type" to "image", "image" to mapOf("link" to it)) }
        return when {
            message.buttons.isNotEmpty() && !buttonsTemplateName.isNullOrBlank() ->
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

            header != null && !imageTemplateName.isNullOrBlank() ->
                template(message.to, imageTemplateName, listOf(headerComponent(header), bodyParameter))

            message.imageUrl != null -> session(message.to, "image", mapOf("link" to message.imageUrl, "caption" to message.body))

            !templateName.isNullOrBlank() -> template(message.to, templateName, listOf(bodyParameter))

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
        internal fun build(
            builder: RestClient.Builder,
            accessToken: String,
            phoneNumberId: String,
            baseUrl: String,
            templateName: String?,
            templateLanguage: String,
            buttonsTemplateName: String? = null,
            imageTemplateName: String? = null,
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
            return MetaCloudWhatsAppSender(client, phoneNumberId, templateName, templateLanguage, buttonsTemplateName, imageTemplateName)
        }
    }
}
