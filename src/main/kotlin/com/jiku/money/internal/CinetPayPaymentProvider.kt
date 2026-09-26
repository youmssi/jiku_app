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
import java.math.BigDecimal
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Currency

/**
 * CinetPay settings (JIKU-106). The adapter is registered only when a site id is
 * set; once registered, every other field is required.
 */
@ConfigurationProperties(prefix = "billing.payment.cinetpay")
data class CinetPayProperties(
    val apiKey: String = "",
    val siteId: String = "",
    val baseUrl: String = "https://api-checkout.cinetpay.com",
    /** Public URL CinetPay notifies: the API's `/billing/payments/callback/cinetpay`. */
    val notifyUrl: String = "",
    /** Where the payer lands after paying; the payment id is appended as `paymentId`. */
    val returnUrl: String = "",
    /** Payment methods offered on CinetPay's page: ALL, MOBILE_MONEY, CREDIT_CARD or WALLET. */
    val channels: String = "ALL",
)

/**
 * CinetPay adapter (ADR 104, §6): Orange Money, MTN MoMo and card through one
 * integration. The payer completes the payment on CinetPay's hosted page.
 *
 * A notification from CinetPay only names the transaction. Its outcome, amount
 * and our reference are always read back from CinetPay's check endpoint with our
 * own credentials, so a forged notification can never settle a payment. The
 * reference travels in `metadata` and the payment id is the `transaction_id`,
 * which keeps both free of CinetPay's format rules.
 */
class CinetPayPaymentProvider(
    private val client: RestClient,
    private val properties: CinetPayProperties,
) : PaymentProvider {
    override val name: String = NAME

    override fun initiate(request: PaymentInitiationRequest): PaymentInitiation {
        val transactionId = request.paymentId.toString()
        val response =
            post(
                "/v2/payment",
                credentials(transactionId) +
                    mapOf(
                        "amount" to toMajorUnits(request.amountMinor, request.currency),
                        "currency" to request.currency,
                        "description" to plainDescription(request.description),
                        "notify_url" to properties.notifyUrl,
                        "return_url" to returnUrlFor(properties.returnUrl, request.paymentId),
                        "channels" to properties.channels,
                        "metadata" to request.reference,
                        "lang" to "fr",
                    ),
            )
        val paymentUrl = response.path("data").text("payment_url")
        if (response.text("code") != CODE_CREATED || paymentUrl == null) {
            throw PaymentProviderException(
                "CinetPay refused payment $transactionId: ${response.text("code")} ${response.text("description")}",
            )
        }
        return PaymentInitiation(
            providerReference = transactionId,
            instruction = PaymentInstruction(type = "REDIRECT", value = paymentUrl),
        )
    }

    override fun parseCallback(
        rawBody: String,
        signature: String?,
    ): PaymentCallback? {
        val form = parseForm(rawBody)
        val siteId = form["cpm_site_id"]
        if (siteId != null && siteId != properties.siteId) return null
        val transactionId = form["cpm_trans_id"]?.takeIf { it.isNotBlank() } ?: return null

        val data = post("/v2/payment/check", credentials(transactionId)).path("data")
        val status = data.text("status") ?: return null
        // The reference must name the payment CinetPay was asked to check.
        val reference = data.text("metadata")?.takeIf { it.endsWith(":$transactionId") } ?: return null
        val currency = data.text("currency")
        return PaymentCallback(
            reference = reference,
            providerReference = transactionId,
            outcome =
                when (status) {
                    STATUS_ACCEPTED -> PaymentOutcome.SUCCEEDED
                    in FINAL_FAILURES -> PaymentOutcome.FAILED
                    else -> PaymentOutcome.PENDING
                },
            amountMinor = currency?.let { code -> data.text("amount")?.let { toMinorUnits(it, code) } },
            currency = currency,
        )
    }

    private fun credentials(transactionId: String): Map<String, Any> =
        mapOf(
            "apikey" to properties.apiKey,
            "site_id" to properties.siteId,
            "transaction_id" to transactionId,
        )

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
                ?: throw PaymentProviderException("CinetPay returned an empty response on $path")
        } catch (ex: HttpStatusCodeException) {
            throw PaymentProviderException("CinetPay rejected $path (${ex.statusCode}): ${ex.responseBodyAsString}", ex)
        } catch (ex: ResourceAccessException) {
            throw PaymentProviderException("CinetPay is unreachable on $path", ex)
        }

    companion object {
        const val NAME = "cinetpay"
        private const val CODE_CREATED = "201"
        private const val STATUS_ACCEPTED = "ACCEPTED"
        private val FINAL_FAILURES = setOf("REFUSED", "CANCELED", "CANCELLED")

        /** CinetPay rejects descriptions carrying special characters. */
        private val UNSAFE_DESCRIPTION = Regex("[^\\p{L}\\p{N} .,'-]")

        internal fun plainDescription(description: String): String = description.replace(UNSAFE_DESCRIPTION, " ").trim()

        internal fun toMajorUnits(
            amountMinor: Long,
            currency: String,
        ): BigDecimal = BigDecimal.valueOf(amountMinor, fractionDigits(currency))

        internal fun toMinorUnits(
            amount: String,
            currency: String,
        ): Long? =
            runCatching {
                BigDecimal(amount).movePointRight(fractionDigits(currency)).longValueExact()
            }.getOrNull()

        private fun fractionDigits(currency: String): Int = Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)

        private fun parseForm(body: String): Map<String, String> =
            body
                .split('&')
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    decode(key) to decode(value)
                }

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

        private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isValueNode }?.asString()?.takeIf { it.isNotBlank() }
    }
}

@Configuration
class CinetPayConfig {
    @Bean
    @ConditionalOnExpression("'\${billing.payment.cinetpay.site-id:}' != ''")
    fun cinetPayPaymentProvider(
        builder: RestClient.Builder,
        properties: CinetPayProperties,
    ): PaymentProvider {
        check(properties.apiKey.isNotBlank()) { "CINETPAY_SITE_ID is set but CINETPAY_API_KEY is not" }
        check(properties.notifyUrl.isNotBlank()) { "CINETPAY_SITE_ID is set but CINETPAY_NOTIFY_URL is not" }
        check(properties.returnUrl.isNotBlank()) { "CINETPAY_SITE_ID is set but CINETPAY_RETURN_URL is not" }
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(Duration.ofSeconds(5))
        factory.setReadTimeout(Duration.ofSeconds(20))
        val client =
            builder
                .clone()
                .baseUrl(properties.baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build()
        return CinetPayPaymentProvider(client, properties)
    }
}
