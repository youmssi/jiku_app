package com.jiku.money.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Duration
import java.util.Currency
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * DEXPAY settings (JIKU-272). The adapter is registered only when a public key is
 * set; once registered, every other field is required. The sandbox answers on
 * `https://api-sandbox.dexpay.africa/api/v1` with `pk_test_`/`sk_test_` keys.
 */
@ConfigurationProperties(prefix = "billing.payment.dexpay")
data class DexPayProperties(
    /** Public key (`pk_…`), sent as `x-api-key` to create checkout sessions. */
    val apiKey: String = "",
    /** Secret key (`sk_…`) of the same key pair: DEXPAY signs its webhooks with it. */
    val apiSecret: String = "",
    val baseUrl: String = "https://api.dexpay.africa/api/v1",
    /** Public URL DEXPAY notifies: the API's `/billing/payments/callback/dexpay`. */
    val webhookUrl: String = "",
    /** Where the payer lands after paying or giving up; the payment id is appended as `paymentId`. */
    val returnUrl: String = "",
    /**
     * Whether the payer pays DEXPAY's fee on top of the price. Off by default: the
     * price shown, invoiced and paid stay the same, and the portal's own setting
     * never decides it.
     */
    val clientPaysFees: Boolean = false,
)

/**
 * DEXPAY adapter (ADR 110, decision 2): Orange Money, MTN, Wave, Moov and card in
 * GNF, XOF and XAF through one checkout session. The payer completes the payment
 * on DEXPAY's hosted page.
 *
 * A webhook is trusted only when its `X-Webhook-Signature` is the HMAC-SHA256 of
 * the raw body under our secret key. The session reference is the payment id and
 * our own reference travels in `metadata`, which DEXPAY echoes back; the two must
 * name the same payment.
 */
class DexPayPaymentProvider(
    private val client: RestClient,
    private val properties: DexPayProperties,
    private val objectMapper: ObjectMapper,
) : PaymentProvider {
    override val name: String = NAME

    override fun initiate(request: PaymentInitiationRequest): PaymentInitiation {
        if (request.currency !in CURRENCIES) {
            throw PaymentProviderException("DEXPAY does not take payments in ${request.currency}")
        }
        val sessionReference = request.paymentId.toString()
        val returnUrl = returnUrlFor(properties.returnUrl, request.paymentId)
        val response =
            post(
                "/checkout-sessions",
                mapOf(
                    "reference" to sessionReference,
                    "item_name" to request.description,
                    "amount" to toDexPayAmount(request.amountMinor, request.currency),
                    "currency" to request.currency,
                    "success_url" to returnUrl,
                    "failure_url" to returnUrl,
                    "webhook_url" to properties.webhookUrl,
                    "client_support_fee" to properties.clientPaysFees,
                    "metadata" to mapOf(METADATA_REFERENCE to request.reference),
                ),
            )
        val paymentUrl =
            response.path("data").text("payment_url")
                ?: throw PaymentProviderException("DEXPAY gave no payment page for payment $sessionReference")
        return PaymentInitiation(
            providerReference = sessionReference,
            instruction = PaymentInstruction(type = "REDIRECT", value = paymentUrl),
        )
    }

    override fun parseCallback(
        rawBody: String,
        signature: String?,
    ): PaymentCallback? {
        if (signature == null || !signatureMatches(rawBody, signature)) return null
        val node = runCatching { objectMapper.readTree(rawBody) }.getOrNull() ?: return null
        val sessionReference = node.text("reference") ?: return null
        // The reference must name the payment this session was opened for.
        val reference = node.path("metadata").text(METADATA_REFERENCE)?.takeIf { it.endsWith(":$sessionReference") } ?: return null
        val currency = node.text("currency")
        return PaymentCallback(
            reference = reference,
            providerReference = sessionReference,
            outcome =
                when (node.text("event")) {
                    EVENT_COMPLETED -> PaymentOutcome.SUCCEEDED
                    in FINAL_FAILURES -> PaymentOutcome.FAILED
                    else -> PaymentOutcome.PENDING
                },
            amountMinor = node.path("amount").takeIf { it.isNumber }?.asLong(),
            currency = currency,
        )
    }

    override fun lookup(providerReference: String): PaymentCallback? {
        val response =
            try {
                client
                    .get()
                    .uri("/checkout-sessions/{reference}", providerReference)
                    .retrieve()
                    .body(JsonNode::class.java)
            } catch (ex: HttpStatusCodeException) {
                if (ex.statusCode.value() == NOT_FOUND) return null
                throw PaymentProviderException("DEXPAY rejected the lookup of $providerReference (${ex.statusCode})", ex)
            } catch (ex: ResourceAccessException) {
                throw PaymentProviderException("DEXPAY is unreachable for the lookup of $providerReference", ex)
            } ?: return null
        // The documentation shows the session both wrapped in `data` and bare.
        val session = response.path("data").takeIf { it.isObject } ?: response
        if (session.text("reference") != providerReference) return null
        val reference = session.path("metadata").text(METADATA_REFERENCE)?.takeIf { it.endsWith(":$providerReference") } ?: return null
        return PaymentCallback(
            reference = reference,
            providerReference = providerReference,
            outcome =
                when (session.text("status")) {
                    STATUS_COMPLETED -> PaymentOutcome.SUCCEEDED
                    in FINAL_FAILED_STATUSES -> PaymentOutcome.FAILED
                    else -> PaymentOutcome.PENDING
                },
            amountMinor = session.path("amount").takeIf { it.isNumber }?.asLong(),
            currency = session.text("currency"),
        )
    }

    private fun signatureMatches(
        rawBody: String,
        signature: String,
    ): Boolean {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(properties.apiSecret.toByteArray(Charsets.UTF_8), HMAC))
        val expected = mac.doFinal(rawBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), signature.trim().lowercase().toByteArray())
    }

    private fun post(
        path: String,
        body: Map<String, Any>,
    ): JsonNode =
        try {
            client
                .post()
                .uri(path)
                .body(body)
                .retrieve()
                .body(JsonNode::class.java)
                ?: throw PaymentProviderException("DEXPAY returned an empty response on $path")
        } catch (ex: HttpStatusCodeException) {
            throw PaymentProviderException("DEXPAY rejected $path (${ex.statusCode}): ${ex.responseBodyAsString}", ex)
        } catch (ex: ResourceAccessException) {
            throw PaymentProviderException("DEXPAY is unreachable on $path", ex)
        }

    companion object {
        const val NAME = "dexpay"
        private const val HMAC = "HmacSHA256"
        private const val METADATA_REFERENCE = "jiku_reference"
        private const val EVENT_COMPLETED = "checkout.completed"
        private val FINAL_FAILURES = setOf("checkout.failed", "checkout.cancelled")
        private const val STATUS_COMPLETED = "completed"
        private val FINAL_FAILED_STATUSES = setOf("failed", "cancelled")
        private const val NOT_FOUND = 404

        /** The currencies DEXPAY collects; all three have no decimals. */
        val CURRENCIES = setOf("GNF", "XOF", "XAF")

        /** DEXPAY amounts are whole units of a currency without decimals, which is also our minor unit. */
        internal fun toDexPayAmount(
            amountMinor: Long,
            currency: String,
        ): Long {
            check(Currency.getInstance(currency).defaultFractionDigits <= 0) { "$currency has decimals; DEXPAY takes whole amounts" }
            return amountMinor
        }

        private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isValueNode }?.asString()?.takeIf { it.isNotBlank() }
    }
}

@Configuration
class DexPayConfig {
    @Bean
    @ConditionalOnExpression("'\${billing.payment.dexpay.api-key:}' != ''")
    fun dexPayPaymentProvider(
        builder: RestClient.Builder,
        properties: DexPayProperties,
        objectMapper: ObjectMapper,
    ): PaymentProvider {
        check(properties.apiSecret.isNotBlank()) { "DEXPAY_API_KEY is set but DEXPAY_API_SECRET is not" }
        check(properties.webhookUrl.isNotBlank()) { "DEXPAY_API_KEY is set but DEXPAY_WEBHOOK_URL is not" }
        check(properties.returnUrl.isNotBlank()) { "DEXPAY_API_KEY is set but DEXPAY_RETURN_URL is not" }
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(Duration.ofSeconds(5))
        factory.setReadTimeout(Duration.ofSeconds(20))
        val client =
            builder
                .clone()
                .baseUrl(properties.baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(API_KEY_HEADER, properties.apiKey)
                .build()
        return DexPayPaymentProvider(client, properties, objectMapper)
    }

    private companion object {
        const val API_KEY_HEADER = "x-api-key"
    }
}
