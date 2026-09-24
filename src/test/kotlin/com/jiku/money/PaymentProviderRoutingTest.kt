package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.money.internal.PaymentCallback
import com.jiku.money.internal.PaymentInitiation
import com.jiku.money.internal.PaymentInitiationRequest
import com.jiku.money.internal.PaymentInstruction
import com.jiku.money.internal.PaymentProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JIKU-105: each provider confirms payments on its own callback URL, and a
 * provider can only settle the payments it started — a second adapter that
 * verifies its own callback still cannot unlock a tier paid through another one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, PaymentProviderRoutingTest.SecondProviderConfig::class)
@TestPropertySource(
    properties = [
        "billing.free-tier-guests=100",
        "billing.payment.provider=sandbox",
        "billing.payment.webhook-secret=test-routing-secret",
    ],
)
class PaymentProviderRoutingTest {
    /** A second adapter that trusts any callback carrying its fixed signature. */
    class SecondProvider : PaymentProvider {
        override val name: String = "second"

        override fun initiate(request: PaymentInitiationRequest) =
            PaymentInitiation(
                providerReference = "SECOND-${request.paymentId}",
                instruction = PaymentInstruction("REDIRECT", "https://second.test"),
            )

        override fun parseCallback(
            rawBody: String,
            signature: String?,
        ): PaymentCallback? {
            if (signature != SECOND_SIGNATURE) return null
            return PaymentCallback(
                reference = JsonPath.read(rawBody, "$.reference"),
                providerReference = "SECOND-x",
                succeeded = true,
            )
        }
    }

    @TestConfiguration
    class SecondProviderConfig {
        @Bean
        fun secondPaymentProvider(): PaymentProvider = SecondProvider()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a provider confirms its own payment on its named callback URL`() {
        val token = register()
        val eventId = createPublishedEvent(token)
        val reference = "${tenantId(token)}:${initiateBronze(token, eventId)}"

        val body = callbackBody(reference)
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback/sandbox")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sign(body))
                    .content(body),
            ).andExpect(status().isOk())

        assert(allowance(token, eventId) == 300)
    }

    @Test
    fun `another provider cannot settle a payment it did not start`() {
        val token = register()
        val eventId = createPublishedEvent(token)
        val reference = "${tenantId(token)}:${initiateBronze(token, eventId)}"

        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback/second")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", SECOND_SIGNATURE)
                    .content(callbackBody(reference)),
            ).andExpect(status().isNotFound())

        assert(allowance(token, eventId) == 100)
    }

    @Test
    fun `a named provider still rejects a callback it cannot verify`() {
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback/second")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", "forged")
                    .content(callbackBody("tenant:payment")),
            ).andExpect(status().isUnauthorized())
    }

    @Test
    fun `a callback for a provider that is not registered is not found`() {
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback/unregistered")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(callbackBody("tenant:payment")),
            ).andExpect(status().isNotFound())
    }

    private fun initiateBronze(
        token: String,
        eventId: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/payments")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"tier":"BRONZE"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.paymentId")
    }

    private fun callbackBody(reference: String): String =
        """{"reference":"$reference","providerReference":"SANDBOX-x","status":"SUCCEEDED"}"""

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-routing-secret".toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun allowance(
        token: String,
        eventId: String,
    ): Int {
        val body =
            mockMvc
                .perform(get("/api/v1/events/$eventId/usage").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.allowance")
    }

    private fun tenantId(token: String): String {
        val body =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.tenantId")
    }

    private fun createPublishedEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Routed Event","timezone":"Africa/Conakry",
                             "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        return eventId
    }

    private fun register(): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Route Org","email":"route-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private companion object {
        const val SECOND_SIGNATURE = "second-provider-signature"
    }
}
