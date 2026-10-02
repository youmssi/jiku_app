package com.jiku.messaging.internal

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * useSend HTTPS API transport (ADR 107), selected with `jiku.mail.transport=usesend`.
 * useSend relays through AWS SES; the base URL points at useSend cloud or at a
 * self-hosted instance, the code is the same. Requires USESEND_API_KEY; the from
 * address must belong to a domain verified in useSend.
 */
@Component
@ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "usesend")
class UseSendEmailSender internal constructor(
    private val client: RestClient,
) : EmailSender {
    @Autowired
    constructor(
        builder: RestClient.Builder,
        @Value("\${jiku.mail.usesend.api-key:}") apiKey: String,
        @Value("\${jiku.mail.usesend.base-url:https://app.usesend.com/api}") baseUrl: String,
    ) : this(buildClient(builder, apiKey, baseUrl))

    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        val recipient =
            if (message.toName.isBlank()) message.to else "${message.toName} <${message.to}>"
        val body =
            buildMap {
                put("from", from)
                put("to", recipient)
                put("subject", message.subject)
                put("html", message.htmlBody)
                if (message.attachments.isNotEmpty()) {
                    put(
                        "attachments",
                        message.attachments.map { mapOf("filename" to it.filename, "content" to it.base64()) },
                    )
                }
            }
        try {
            client
                .post()
                .uri("/v1/emails")
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpStatusCodeException) {
            throw EmailDeliveryException(
                "useSend API rejected message to ${message.to} (${e.statusCode}): ${e.responseBodyAsString}",
                e,
            )
        } catch (e: ResourceAccessException) {
            throw EmailDeliveryException("useSend API unreachable for ${message.to}", e)
        }
    }

    companion object {
        internal fun buildClient(
            builder: RestClient.Builder,
            apiKey: String,
            baseUrl: String,
        ): RestClient {
            check(apiKey.isNotBlank()) {
                "jiku.mail.transport=usesend but jiku.mail.usesend.api-key (USESEND_API_KEY) is not set"
            }
            val factory = SimpleClientHttpRequestFactory()
            factory.setConnectTimeout(Duration.ofSeconds(5))
            factory.setReadTimeout(Duration.ofSeconds(15))
            return builder
                .baseUrl(baseUrl.trimEnd('/'))
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
        }
    }
}
