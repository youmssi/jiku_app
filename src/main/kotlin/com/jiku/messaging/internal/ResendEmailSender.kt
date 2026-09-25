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
 * Resend HTTPS API transport, selected with `jiku.mail.transport=resend`. Used in
 * production, where the hosting platform (Render) blocks outbound SMTP. Requires
 * RESEND_API_KEY; the from address must belong to a domain verified in Resend.
 */
@Component
@ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "resend")
class ResendEmailSender internal constructor(
    private val client: RestClient,
) : EmailSender {
    @Autowired
    constructor(
        builder: RestClient.Builder,
        @Value("\${jiku.mail.resend.api-key:}") apiKey: String,
        @Value("\${jiku.mail.resend.base-url:https://api.resend.com}") baseUrl: String,
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
                put("to", listOf(recipient))
                put("subject", message.subject)
                put("html", message.htmlBody)
                if (message.attachments.isNotEmpty()) {
                    put(
                        "attachments",
                        message.attachments.map {
                            mapOf("filename" to it.filename, "content" to it.base64(), "content_type" to it.contentType)
                        },
                    )
                }
            }
        try {
            client
                .post()
                .uri("/emails")
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpStatusCodeException) {
            throw EmailDeliveryException(
                "Resend API rejected message to ${message.to} (${e.statusCode}): ${e.responseBodyAsString}",
                e,
            )
        } catch (e: ResourceAccessException) {
            throw EmailDeliveryException("Resend API unreachable for ${message.to}", e)
        }
    }

    companion object {
        internal fun buildClient(
            builder: RestClient.Builder,
            apiKey: String,
            baseUrl: String,
        ): RestClient {
            check(apiKey.isNotBlank()) {
                "jiku.mail.transport=resend but jiku.mail.resend.api-key (RESEND_API_KEY) is not set"
            }
            val factory = SimpleClientHttpRequestFactory()
            factory.setConnectTimeout(Duration.ofSeconds(5))
            factory.setReadTimeout(Duration.ofSeconds(15))
            return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $apiKey")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
        }
    }
}
