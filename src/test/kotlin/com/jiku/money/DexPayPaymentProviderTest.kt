package com.jiku.money

import com.jiku.money.internal.DexPayPaymentProvider
import com.jiku.money.internal.DexPayProperties
import com.jiku.money.internal.PaymentInitiationRequest
import com.jiku.money.internal.PaymentOutcome
import com.jiku.money.internal.PaymentProviderException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JIKU-272: DEXPAY opens a checkout session on its hosted page, and a webhook
 * settles a payment only when its signature is the HMAC of the raw body under our
 * secret key and its metadata names the same payment.
 */
class DexPayPaymentProviderTest {
    private val properties =
        DexPayProperties(
            apiKey = "pk_test_key",
            apiSecret = "sk_test_secret",
            baseUrl = "https://dexpay.test/api/v1",
            webhookUrl = "https://api.jiku.test/api/v1/billing/payments/callback/dexpay",
            returnUrl = "https://jiku.test/billing/return",
        )
    private val paymentId = UUID.randomUUID()
    private val reference = "${UUID.randomUUID()}:$paymentId"
    private val builder = RestClient.builder().baseUrl(properties.baseUrl)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val provider = DexPayPaymentProvider(builder.build(), properties, JsonMapper.builder().build())

    @Test
    fun `opens a checkout session on DEXPAY's hosted page`() {
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request ->
                val body = (request as MockClientHttpRequest).bodyAsString
                assertTrue(body.contains("\"reference\":\"$paymentId\""), body)
                assertTrue(body.contains("\"amount\":150000"), body)
                assertTrue(body.contains("\"currency\":\"GNF\""), body)
                assertTrue(body.contains("\"item_name\":\"300 guests\""), body)
                assertTrue(body.contains("\"success_url\":\"https://jiku.test/billing/return?paymentId=$paymentId\""), body)
                assertTrue(body.contains("\"failure_url\":\"https://jiku.test/billing/return?paymentId=$paymentId\""), body)
                assertTrue(body.contains("\"webhook_url\":\"${properties.webhookUrl}\""), body)
                assertTrue(body.contains("\"jiku_reference\":\"$reference\""), body)
                assertTrue(body.contains("\"client_support_fee\":false"), body)
            }.andRespond(
                withStatus(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        """{"status":201,"data":{"reference":"$paymentId","payment_url":"https://pay.dexpay.test/$paymentId","status":"initiated"}}""",
                    ),
            )

        val initiation = provider.initiate(request())

        assertEquals("REDIRECT", initiation.instruction.type)
        assertEquals("https://pay.dexpay.test/$paymentId", initiation.instruction.value)
        assertEquals(paymentId.toString(), initiation.providerReference)
        server.verify()
    }

    @Test
    fun `a refused session surfaces DEXPAY's reason`() {
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"statusCode":400,"message":"A checkout session with reference already exists","error":"Bad Request"}"""),
            )

        val failure = assertFailsWith<PaymentProviderException> { provider.initiate(request()) }

        assertTrue(failure.message!!.contains("already exists"), failure.message)
    }

    @Test
    fun `a session without a payment page is a failure`() {
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions"))
            .andRespond(withSuccess("""{"status":201,"data":{}}""", MediaType.APPLICATION_JSON))

        assertFailsWith<PaymentProviderException> { provider.initiate(request()) }
    }

    @Test
    fun `a currency DEXPAY does not collect is never sent`() {
        assertFailsWith<PaymentProviderException> { provider.initiate(request(currency = "USD", amountMinor = 9_000)) }
        server.verify()
    }

    @Test
    fun `a signed completed webhook settles the payment with its amount`() {
        val body = webhook("checkout.completed")

        val callback = provider.parseCallback(body, sign(body))!!

        assertEquals(PaymentOutcome.SUCCEEDED, callback.outcome)
        assertEquals(reference, callback.reference)
        assertEquals(paymentId.toString(), callback.providerReference)
        assertEquals(150_000L, callback.amountMinor)
        assertEquals("GNF", callback.currency)
    }

    @Test
    fun `a failed or cancelled payment is final, a started one is not`() {
        listOf("checkout.failed", "checkout.cancelled").forEach { event ->
            val body = webhook(event)
            assertEquals(PaymentOutcome.FAILED, provider.parseCallback(body, sign(body))!!.outcome, event)
        }
        val started = webhook("checkout.initiated")
        assertEquals(PaymentOutcome.PENDING, provider.parseCallback(started, sign(started))!!.outcome)
    }

    @Test
    fun `a webhook without the right signature is refused`() {
        val body = webhook("checkout.completed")

        assertNull(provider.parseCallback(body, null))
        assertNull(provider.parseCallback(body, sign(body, "sk_test_other")))
        assertNull(provider.parseCallback(body.replace("150000", "1"), sign(body)))
    }

    @Test
    fun `a webhook whose metadata names another payment is refused`() {
        val body = webhook("checkout.completed", metadataReference = "${UUID.randomUUID()}:${UUID.randomUUID()}")

        assertNull(provider.parseCallback(body, sign(body)))
    }

    @Test
    fun `a lookup reads the session's outcome with its amount`() {
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions/$paymentId"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(session("completed"), MediaType.APPLICATION_JSON))

        val callback = provider.lookup(paymentId.toString())!!

        assertEquals(PaymentOutcome.SUCCEEDED, callback.outcome)
        assertEquals(reference, callback.reference)
        assertEquals(150_000L, callback.amountMinor)
        assertEquals("GNF", callback.currency)
        server.verify()
    }

    @Test
    fun `a lookup also reads a session wrapped in data, and maps unfinished and failed sessions`() {
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions/$paymentId"))
            .andRespond(withSuccess("""{"status":200,"data":${session("initiated")}}""", MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions/$paymentId"))
            .andRespond(withSuccess(session("cancelled"), MediaType.APPLICATION_JSON))

        assertEquals(PaymentOutcome.PENDING, provider.lookup(paymentId.toString())!!.outcome)
        assertEquals(PaymentOutcome.FAILED, provider.lookup(paymentId.toString())!!.outcome)
    }

    @Test
    fun `a lookup of an unknown session or one naming another payment gives nothing`() {
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions/$paymentId"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions/$paymentId"))
            .andRespond(
                withSuccess(
                    session("completed", metadataReference = "${UUID.randomUUID()}:${UUID.randomUUID()}"),
                    MediaType.APPLICATION_JSON,
                ),
            )

        assertNull(provider.lookup(paymentId.toString()))
        assertNull(provider.lookup(paymentId.toString()))
    }

    @Test
    fun `an unreachable DEXPAY during a lookup is reported, not taken as unpaid`() {
        server
            .expect(requestTo("https://dexpay.test/api/v1/checkout-sessions/$paymentId"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        assertFailsWith<PaymentProviderException> { provider.lookup(paymentId.toString()) }
    }

    private fun session(
        status: String,
        metadataReference: String = reference,
    ): String =
        """{"id":"507f1f77bcf86cd799439011","reference":"$paymentId","amount":150000,"currency":"GNF",""" +
            """"status":"$status","metadata":{"jiku_reference":"$metadataReference"}}"""

    private fun request(
        currency: String = "GNF",
        amountMinor: Long = 150_000,
    ) = PaymentInitiationRequest(
        paymentId = paymentId,
        amountMinor = amountMinor,
        currency = currency,
        description = "300 guests",
        reference = reference,
    )

    private fun webhook(
        event: String,
        metadataReference: String = reference,
    ): String =
        """{"event":"$event","reference":"$paymentId","checkout_session_id":"507f1f77bcf86cd799439011",""" +
            """"status":"x","amount":150000,"currency":"GNF","timestamp":"2026-10-27T12:05:00Z",""" +
            """"metadata":{"jiku_reference":"$metadataReference"}}"""

    private fun sign(
        body: String,
        secret: String = properties.apiSecret,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
