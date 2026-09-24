package com.jiku.money

import com.jiku.money.internal.CinetPayPaymentProvider
import com.jiku.money.internal.CinetPayProperties
import com.jiku.money.internal.PaymentInitiationRequest
import com.jiku.money.internal.PaymentOutcome
import com.jiku.money.internal.PaymentProviderException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.ExpectedCount.never
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JIKU-106: CinetPay starts a payment on its hosted page, and a notification only
 * settles a payment once CinetPay's own check endpoint confirms its outcome.
 */
class CinetPayPaymentProviderTest {
    private val properties =
        CinetPayProperties(
            apiKey = "key-1",
            siteId = "site-1",
            baseUrl = "https://cinetpay.test",
            notifyUrl = "https://api.jiku.test/api/v1/billing/payments/callback/cinetpay",
            returnUrl = "https://jiku.test/billing/return",
        )
    private val paymentId = UUID.randomUUID()
    private val reference = "${UUID.randomUUID()}:$paymentId"
    private val builder = RestClient.builder().baseUrl(properties.baseUrl)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val provider = CinetPayPaymentProvider(builder.build(), properties)

    @Test
    fun `starts a payment on CinetPay's hosted page`() {
        server
            .expect(requestTo("https://cinetpay.test/v2/payment"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request ->
                val body = (request as MockClientHttpRequest).bodyAsString
                assertTrue(body.contains("\"transaction_id\":\"$paymentId\""))
                assertTrue(body.contains("\"amount\":150000"))
                assertTrue(body.contains("\"currency\":\"GNF\""))
                assertTrue(body.contains("\"metadata\":\"$reference\""))
                assertTrue(body.contains("\"description\":\"Unlock BRONZE tier\""))
            }.andRespond(
                withSuccess(
                    """{"code":"201","message":"CREATED","data":{"payment_token":"tok","payment_url":"https://checkout.cinetpay.test/p/tok"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val initiation = provider.initiate(request())

        assertEquals("REDIRECT", initiation.instruction.type)
        assertEquals("https://checkout.cinetpay.test/p/tok", initiation.instruction.value)
        assertEquals(paymentId.toString(), initiation.providerReference)
        server.verify()
    }

    @Test
    fun `a refused initiation surfaces CinetPay's reason`() {
        server
            .expect(requestTo("https://cinetpay.test/v2/payment"))
            .andRespond(withSuccess("""{"code":"608","description":"MINIMUM_REQUIRED_FIELDS"}""", MediaType.APPLICATION_JSON))

        val failure = assertFailsWith<PaymentProviderException> { provider.initiate(request()) }

        assertTrue(failure.message!!.contains("MINIMUM_REQUIRED_FIELDS"))
    }

    @Test
    fun `an accepted payment is confirmed by the check endpoint, with the amount paid`() {
        expectCheck("ACCEPTED")

        val callback = provider.parseCallback(notification(), signature = null)!!

        assertEquals(PaymentOutcome.SUCCEEDED, callback.outcome)
        assertEquals(reference, callback.reference)
        assertEquals(150_000L, callback.amountMinor)
        assertEquals("GNF", callback.currency)
        server.verify()
    }

    @Test
    fun `a payment still awaiting the payer stays pending`() {
        expectCheck("WAITING_CUSTOMER_PAYMENT")

        assertEquals(PaymentOutcome.PENDING, provider.parseCallback(notification(), null)!!.outcome)
    }

    @Test
    fun `a refused payment is final`() {
        expectCheck("REFUSED")

        assertEquals(PaymentOutcome.FAILED, provider.parseCallback(notification(), null)!!.outcome)
    }

    @Test
    fun `a notification for another site is rejected without calling CinetPay`() {
        server.expect(never(), requestTo("https://cinetpay.test/v2/payment/check"))

        assertNull(provider.parseCallback(notification(siteId = "other-site"), null))
        server.verify()
    }

    @Test
    fun `a check whose reference names another payment is rejected`() {
        expectCheck("ACCEPTED", metadata = "${UUID.randomUUID()}:${UUID.randomUUID()}")

        assertNull(provider.parseCallback(notification(), null))
    }

    @Test
    fun `a transaction CinetPay does not know is rejected`() {
        server
            .expect(requestTo("https://cinetpay.test/v2/payment/check"))
            .andRespond(withSuccess("""{"code":"627","message":"TRANSACTION_NOT_FOUND"}""", MediaType.APPLICATION_JSON))

        assertNull(provider.parseCallback(notification(), null))
    }

    @Test
    fun `an unreachable check endpoint is reported, not taken as a failure`() {
        server
            .expect(requestTo("https://cinetpay.test/v2/payment/check"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY))

        assertFailsWith<PaymentProviderException> { provider.parseCallback(notification(), null) }
    }

    @Test
    fun `amounts follow the currency's minor unit`() {
        assertEquals("150000", CinetPayPaymentProvider.toMajorUnits(150_000, "GNF").toPlainString())
        assertEquals("12.50", CinetPayPaymentProvider.toMajorUnits(1_250, "USD").toPlainString())
        assertEquals(1_250L, CinetPayPaymentProvider.toMinorUnits("12.5", "USD"))
        assertNull(CinetPayPaymentProvider.toMinorUnits("12.505", "USD"))
    }

    private fun request() =
        PaymentInitiationRequest(
            paymentId = paymentId,
            amountMinor = 150_000,
            currency = "GNF",
            description = "Unlock BRONZE tier",
            reference = reference,
        )

    private fun notification(siteId: String = "site-1") = "cpm_site_id=$siteId&cpm_trans_id=$paymentId&cpm_amount=150000"

    private fun expectCheck(
        status: String,
        metadata: String = reference,
    ) {
        server
            .expect(requestTo("https://cinetpay.test/v2/payment/check"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request ->
                val body = (request as MockClientHttpRequest).bodyAsString
                assertTrue(body.contains("\"apikey\":\"key-1\""))
                assertTrue(body.contains("\"transaction_id\":\"$paymentId\""))
            }.andRespond(
                withSuccess(
                    """{"code":"00","message":"SUCCES","data":{"amount":"150000","currency":"GNF","status":"$status","metadata":"$metadata"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
    }
}
