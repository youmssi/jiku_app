package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PUBLIC_KEY = "pk_test_flow"
private const val SECRET_KEY = "sk_test_flow"

/**
 * JIKU-272 against a stand-in for DEXPAY: a purchase in GNF opens a checkout
 * session with our public key, and only a webhook signed with our secret key
 * grants it.
 */
@SpringBootTest(
    properties = [
        "billing.payment.by-currency.GNF=dexpay",
        "billing.payment.dexpay.api-key=$PUBLIC_KEY",
        "billing.payment.dexpay.api-secret=$SECRET_KEY",
        "billing.payment.dexpay.webhook-url=https://api.jiku.test/api/v1/billing/payments/callback/dexpay",
        "billing.payment.dexpay.return-url=https://jiku.test/billing/return",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DexPayCheckoutFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a pack paid through DEXPAY is granted by its signed webhook only`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val tenantId = api.tenantId(token)

        val checkout =
            api
                .post(token, "/api/v1/billing/pack/checkout", """{"months":1}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instruction.type").value("REDIRECT"))
                .andReturn()
                .response.contentAsString
        val paymentId = JsonPath.read<String>(checkout, "$.paymentId")
        assertEquals("https://pay.dexpay.test/$paymentId", JsonPath.read<String>(checkout, "$.instruction.value"))

        val session = sessions.single { "\"reference\":\"$paymentId\"" in it.body }
        assertEquals(PUBLIC_KEY, session.apiKey)
        assertTrue("\"amount\":600000" in session.body, session.body)
        assertTrue("\"currency\":\"GNF\"" in session.body, session.body)
        assertTrue("\"jiku_reference\":\"$tenantId:$paymentId\"" in session.body, session.body)

        val body =
            """{"event":"checkout.completed","reference":"$paymentId","status":"completed","amount":600000,""" +
                """"currency":"GNF","metadata":{"jiku_reference":"$tenantId:$paymentId"}}"""
        webhook(body, sign(body, "sk_test_other")).andExpect(status().isUnauthorized())
        api.get(token, "/api/v1/billing/payments/$paymentId").andExpect(jsonPath("$.status").value("PENDING"))

        webhook(body, sign(body, SECRET_KEY)).andExpect(status().isOk())
        api.get(token, "/api/v1/billing/payments/$paymentId").andExpect(jsonPath("$.status").value("SUCCEEDED"))
        api.get(token, "/api/v1/billing/pack").andExpect(jsonPath("$.active").value(true))
    }

    private fun webhook(
        body: String,
        signature: String,
    ) = mockMvc.perform(
        post("/api/v1/billing/payments/callback/dexpay")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Webhook-Signature", signature)
            .content(body),
    )

    private fun sign(
        body: String,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    data class Session(
        val apiKey: String?,
        val body: String,
    )

    companion object {
        val sessions = CopyOnWriteArrayList<Session>()

        /** Stands in for DEXPAY's checkout API and records the sessions Jikū opened. */
        private val dexpay =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/api/v1/checkout-sessions") { exchange ->
                    val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    sessions += Session(exchange.requestHeaders.getFirst("x-api-key"), body)
                    val reference = Regex("\"reference\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    val bytes =
                        """{"status":201,"data":{"reference":"$reference","payment_url":"https://pay.dexpay.test/$reference","status":"initiated"}}"""
                            .toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(201, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun dexpayBaseUrl(registry: DynamicPropertyRegistry) {
            registry.add("billing.payment.dexpay.base-url") { "http://127.0.0.1:${dexpay.address.port}/api/v1" }
        }

        @JvmStatic
        @AfterAll
        fun stopDexpay() {
            dexpay.stop(0)
        }
    }
}
