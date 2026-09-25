package com.jiku.messaging.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Duration

/**
 * The Graph API calls that finish Embedded Signup (ADR 105): once the
 * organizer approves Jikū in Meta's window, the code it returns becomes a
 * business token, Jikū subscribes to the account's messages, registers the
 * number for the Cloud API, reads its display name and creates Jikū's
 * templates in the account. The token stays with the organization's
 * credentials; Meta bills its messages to the organization.
 */
@Component
class MetaOnboardingClient(
    builder: RestClient.Builder,
    private val properties: WhatsAppProperties,
) {
    private val client: RestClient =
        builder
            .clone()
            .baseUrl(properties.meta.baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(5))
                    setReadTimeout(Duration.ofSeconds(20))
                },
            ).defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()

    /** Trades the code from Meta's window for a business integration token. */
    fun exchangeCode(code: String): String =
        call("exchange the signup code") {
            client
                .get()
                .uri {
                    it
                        .path("/oauth/access_token")
                        .queryParam("client_id", properties.meta.appId)
                        .queryParam("client_secret", properties.meta.appSecret)
                        .queryParam("code", code)
                        .build()
                }.retrieve()
                .body(JsonNode::class.java)
        }?.path("access_token")?.asString("")?.takeIf { it.isNotBlank() }
            ?: throw WhatsAppOnboardingException("Meta returned no access token for the signup code")

    /** Delivers the account's messages to Jikū's webhook. */
    fun subscribeApp(
        wabaId: String,
        token: String,
    ) {
        call("subscribe to the WhatsApp account") {
            client
                .post()
                .uri(
                    "/{wabaId}/subscribed_apps",
                    wabaId,
                ).header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .retrieve()
                .toBodilessEntity()
        }
    }

    /** Registers the number for the Cloud API with its two-step verification [pin]. */
    fun register(
        phoneNumberId: String,
        token: String,
        pin: String,
    ) {
        call("register the number") {
            client
                .post()
                .uri("/{phoneNumberId}/register", phoneNumberId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body(mapOf("messaging_product" to "whatsapp", "pin" to pin))
                .retrieve()
                .toBodilessEntity()
        }
    }

    fun phoneNumber(
        phoneNumberId: String,
        token: String,
    ): BusinessPhoneNumber {
        val node =
            call("read the number") {
                client
                    .get()
                    .uri { it.path("/{phoneNumberId}").queryParam("fields", "display_phone_number,verified_name").build(phoneNumberId) }
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(JsonNode::class.java)
            }
        return BusinessPhoneNumber(
            displayPhoneNumber = node?.path("display_phone_number")?.asString("")?.takeIf { it.isNotBlank() },
            verifiedName = node?.path("verified_name")?.asString("")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Uploads [bytes] through the app's resumable upload API and returns the
     * handle a template's image header gives Meta as its example.
     */
    fun uploadExample(
        token: String,
        fileName: String,
        contentType: String,
        bytes: ByteArray,
    ): String {
        val session =
            call("start the example upload") {
                client
                    .post()
                    .uri {
                        it
                            .path("/{appId}/uploads")
                            .queryParam("file_name", fileName)
                            .queryParam("file_length", bytes.size)
                            .queryParam("file_type", contentType)
                            .build(properties.meta.appId)
                    }.header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .retrieve()
                    .body(JsonNode::class.java)
            }?.path("id")?.asString("")?.takeIf { UPLOAD_SESSION.matches(it) }
                ?: throw WhatsAppOnboardingException("Meta returned no upload session")
        return call("upload the example") {
            client
                .post()
                .uri(URI.create(properties.meta.baseUrl.trimEnd('/') + "/" + session))
                .header(HttpHeaders.AUTHORIZATION, "OAuth $token")
                .header("file_offset", "0")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .retrieve()
                .body(JsonNode::class.java)
        }?.path("h")?.asString("")?.takeIf { it.isNotBlank() }
            ?: throw WhatsAppOnboardingException("Meta returned no handle for the example")
    }

    fun createTemplate(
        wabaId: String,
        token: String,
        template: Map<String, Any>,
    ) {
        call("create template ${template["name"]}") {
            client
                .post()
                .uri("/{wabaId}/message_templates", wabaId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body(template)
                .retrieve()
                .toBodilessEntity()
        }
    }

    private fun <T> call(
        what: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: HttpStatusCodeException) {
            throw WhatsAppOnboardingException("Meta refused to $what (${e.statusCode}): ${e.responseBodyAsString.take(300)}", e)
        } catch (e: ResourceAccessException) {
            throw WhatsAppOnboardingException("Meta was unreachable to $what", e)
        }
}

/** Meta names an upload session `upload:<id>`; the colon must reach it as is. */
private val UPLOAD_SESSION = Regex("upload:[A-Za-z0-9_=\\-]+(\\?[A-Za-z0-9_=&\\-]*)?")

data class BusinessPhoneNumber(
    val displayPhoneNumber: String?,
    val verifiedName: String?,
)

class WhatsAppOnboardingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
