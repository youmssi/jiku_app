package com.jiku.notification.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * Meta WhatsApp Cloud API adapter (JIKU-43). Sends through the official Graph
 * API, either as a plain text message (works inside the 24-hour customer-service
 * window and with test numbers) or, when a template name is configured, as an
 * approved template message whose single body parameter carries the rendered
 * text — the pattern for business-initiated sends outside the 24-hour window.
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
) : WhatsAppSender {
    override fun send(message: WhatsAppMessage) {
        val body =
            if (templateName.isNullOrBlank()) {
                mapOf(
                    "messaging_product" to "whatsapp",
                    "to" to message.to,
                    "type" to "text",
                    "text" to mapOf("body" to message.body),
                )
            } else {
                mapOf(
                    "messaging_product" to "whatsapp",
                    "to" to message.to,
                    "type" to "template",
                    "template" to
                        mapOf(
                            "name" to templateName,
                            "language" to mapOf("code" to templateLanguage),
                            "components" to
                                listOf(
                                    mapOf(
                                        "type" to "body",
                                        "parameters" to
                                            listOf(mapOf("type" to "text", "text" to message.body)),
                                    ),
                                ),
                        ),
                )
            }
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

    companion object {
        internal fun build(
            builder: RestClient.Builder,
            accessToken: String,
            phoneNumberId: String,
            baseUrl: String,
            templateName: String?,
            templateLanguage: String,
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
            return MetaCloudWhatsAppSender(client, phoneNumberId, templateName, templateLanguage)
        }
    }
}
