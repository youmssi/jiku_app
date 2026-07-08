package com.jiku.billing.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Default payment provider used until a real Mobile Money provider is wired in. It
 * lets the full flow work end to end — initiation returns a return-URL instruction,
 * and callbacks are verified with a real HMAC-SHA256 signature over the raw body,
 * exercising the same verification path a real provider would. A concrete provider
 * replaces it automatically by defining another [PaymentProvider] bean.
 */
class SandboxPaymentProvider(
    private val properties: PaymentProperties,
    private val objectMapper: ObjectMapper,
) : PaymentProvider {
    override val name: String = "sandbox"

    override fun initiate(request: PaymentInitiationRequest): PaymentInitiation =
        PaymentInitiation(
            providerReference = "SANDBOX-${request.paymentId}",
            instruction =
                PaymentInstruction(
                    type = "REDIRECT",
                    value = "${properties.sandboxReturnUrl}?ref=${request.reference}",
                ),
        )

    override fun parseCallback(
        rawBody: String,
        signature: String?,
    ): PaymentCallback? {
        if (signature == null || !constantTimeEquals(hmacSha256Hex(rawBody), signature)) {
            return null
        }
        val node = objectMapper.readTree(rawBody)
        val reference = node.get("reference")?.asString() ?: return null
        return PaymentCallback(
            reference = reference,
            providerReference = node.get("providerReference")?.asString() ?: "",
            succeeded = (node.get("status")?.asString() ?: "") == "SUCCEEDED",
        )
    }

    private fun hmacSha256Hex(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.webhookSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}

@Configuration
class PaymentProviderConfig {
    @Bean
    @ConditionalOnMissingBean(PaymentProvider::class)
    fun sandboxPaymentProvider(
        properties: PaymentProperties,
        objectMapper: ObjectMapper,
    ): PaymentProvider = SandboxPaymentProvider(properties, objectMapper)
}
