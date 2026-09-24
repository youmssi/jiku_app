package com.jiku.messaging.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.util.Base64

/**
 * Nimba SMS adapter (JIKU-112): a Guinean provider connected directly to Orange,
 * MTN and Cellcom, sending under the organization-approved sender name.
 * `POST /v1/messages` with HTTP Basic credentials (service id, secret token).
 */
class NimbaSmsSender internal constructor(
    private val client: RestClient,
    private val senderName: String,
) : SmsSender {
    override fun send(message: SmsMessage) {
        val body =
            mapOf(
                "to" to listOf(message.to.filterNot { it.isWhitespace() }),
                "sender_name" to senderName,
                "message" to message.body,
            )
        try {
            client
                .post()
                .uri("/v1/messages")
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (ex: HttpStatusCodeException) {
            throw SmsDeliveryException("Nimba SMS rejected the message to ${message.to} (${ex.statusCode}): ${ex.responseBodyAsString}", ex)
        } catch (ex: ResourceAccessException) {
            throw SmsDeliveryException("Nimba SMS is unreachable for ${message.to}", ex)
        }
    }

    companion object {
        internal fun build(
            builder: RestClient.Builder,
            properties: SmsProperties.Nimba,
        ): NimbaSmsSender {
            check(properties.serviceId.isNotBlank() && properties.secretToken.isNotBlank()) {
                "jiku.sms.transport=nimba but NIMBA_SMS_SERVICE_ID or NIMBA_SMS_SECRET_TOKEN is not set"
            }
            check(properties.senderName.isNotBlank()) { "jiku.sms.transport=nimba but NIMBA_SMS_SENDER_NAME is not set" }
            val credentials = Base64.getEncoder().encodeToString("${properties.serviceId}:${properties.secretToken}".toByteArray())
            val client =
                builder
                    .baseUrl(properties.baseUrl)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic $credentials")
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
            return NimbaSmsSender(client, properties.senderName)
        }
    }
}
