package com.jiku.notification.internal

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
 * Brevo transactional email API transport (JIKU-62). Selected standalone with
 * `jiku.mail.transport=brevo`, or used as the fallback leg of
 * [RoutingEmailSender] (`jiku.mail.transport=routing`) once Resend's daily cap
 * is reached — Brevo's free tier (300/day) absorbs volume Resend's free tier
 * (100/day) alone cannot, without paying either provider.
 */
@Component
@ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "brevo")
class BrevoEmailSender internal constructor(
    private val client: RestClient,
) : EmailSender {
    @Autowired
    constructor(
        builder: RestClient.Builder,
        @Value("\${jiku.mail.brevo.api-key:}") apiKey: String,
        @Value("\${jiku.mail.brevo.base-url:https://api.brevo.com/v3}") baseUrl: String,
    ) : this(buildClient(builder, apiKey, baseUrl))

    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        val (fromEmail, fromName) = parseFrom(from)
        val sender = if (fromName != null) mapOf("email" to fromEmail, "name" to fromName) else mapOf("email" to fromEmail)
        val recipient =
            if (message.toName.isBlank()) mapOf("email" to message.to) else mapOf("email" to message.to, "name" to message.toName)
        val body =
            mapOf(
                "sender" to sender,
                "to" to listOf(recipient),
                "subject" to message.subject,
                "htmlContent" to message.htmlBody,
            )
        try {
            client
                .post()
                .uri("/smtp/email")
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (e: HttpStatusCodeException) {
            throw EmailDeliveryException(
                "Brevo API rejected message to ${message.to} (${e.statusCode}): ${e.responseBodyAsString}",
                e,
            )
        } catch (e: ResourceAccessException) {
            throw EmailDeliveryException("Brevo API unreachable for ${message.to}", e)
        }
    }

    companion object {
        private val FROM_PATTERN = Regex("^(.*)<(.+)>$")

        /** Parses Resend-style `"Name <email>"` (or a bare email) into (email, name?). */
        internal fun parseFrom(from: String): Pair<String, String?> {
            val match = FROM_PATTERN.find(from.trim())
            return if (match != null) {
                match.groupValues[2].trim() to match.groupValues[1].trim().ifBlank { null }
            } else {
                from.trim() to null
            }
        }

        internal fun buildClient(
            builder: RestClient.Builder,
            apiKey: String,
            baseUrl: String,
        ): RestClient {
            check(apiKey.isNotBlank()) {
                "jiku.mail.transport=brevo (or routing) but jiku.mail.brevo.api-key (BREVO_API_KEY) is not set"
            }
            val factory = SimpleClientHttpRequestFactory()
            factory.setConnectTimeout(Duration.ofSeconds(5))
            factory.setReadTimeout(Duration.ofSeconds(15))
            return builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("api-key", apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build()
        }
    }
}
