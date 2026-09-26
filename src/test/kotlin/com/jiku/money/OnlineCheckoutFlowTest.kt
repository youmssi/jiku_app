package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JIKU-164: every purchase can be paid online, not only an event tier. A checkout
 * records a pending payment and hands back the provider's page, whose return URL
 * names the payment; the purchase is granted only when the provider confirms it,
 * and the payment's status is readable by its own organization alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "billing.payment.webhook-secret=$SECRET",
        "billing.payment.sandbox-return-url=https://jiku.test/billing/return",
    ],
)
class OnlineCheckoutFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private lateinit var api: OrganizerApi

    @BeforeEach
    fun setUp() {
        api = OrganizerApi(mockMvc)
    }

    @Test
    fun `a pack paid online is granted once the provider confirms it`() {
        val token = api.register()
        val tenantId = api.tenantId(token)

        val checkout =
            api
                .post(token, "/api/v1/billing/pack/checkout", """{"months":1}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountMinor").value(600_000))
                .andExpect(jsonPath("$.instruction.type").value("REDIRECT"))
                .andReturn()
                .response.contentAsString
        val paymentId = JsonPath.read<String>(checkout, "$.paymentId")
        val returnUrl = JsonPath.read<String>(checkout, "$.instruction.value")
        assertTrue(returnUrl.startsWith("https://jiku.test/billing/return?paymentId=$paymentId"), returnUrl)

        api
            .get(token, "/api/v1/billing/payments/$paymentId")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.kind").value("PACK"))
            .andExpect(jsonPath("$.months").value(1))
            .andExpect(jsonPath("$.status").value("PENDING"))
        api.get(token, "/api/v1/billing/pack").andExpect(jsonPath("$.active").value(false))

        callback("$tenantId:$paymentId", "SUCCEEDED", amount = 600_000, currency = "GNF")

        api.get(token, "/api/v1/billing/payments/$paymentId").andExpect(jsonPath("$.status").value("SUCCEEDED"))
        api
            .get(token, "/api/v1/billing/pack")
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.remainingGuests").value(1_000))

        api.get(api.register(), "/api/v1/billing/payments/$paymentId").andExpect(status().isNotFound())
    }

    @Test
    fun `extra guests, the own number and a plan are all paid online`() {
        val token = api.register()
        val tenantId = api.tenantId(token)

        pay(token, tenantId, "/api/v1/billing/whatsapp-number/checkout", """{"months":1}""")
        api
            .get(token, "/api/v1/billing/whatsapp-number")
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.source").value("ADDON"))

        api.post(token, "/api/v1/billing/pack/extra/checkout", """{"blocks":1}""").andExpect(status().isConflict())
        pay(token, tenantId, "/api/v1/billing/pack/checkout", """{"months":1}""")
        val extra = pay(token, tenantId, "/api/v1/billing/pack/extra/checkout", """{"blocks":1}""")
        api.get(token, "/api/v1/billing/payments/$extra").andExpect(jsonPath("$.kind").value("PACK_EXTRA"))
        val pack =
            api
                .get(token, "/api/v1/billing/pack")
                .andReturn()
                .response.contentAsString
        assertEquals(JsonPath.read<Int>(pack, "$.extraBlockGuests"), JsonPath.read<Int>(pack, "$.extraGuests"))

        val team = api.register()
        val teamTenant = api.tenantId(team)
        repeat(2) {
            api
                .post(team, "/api/v1/resources", """{"name":"Poste ${UUID.randomUUID()}","type":"PERSON","timezone":"Africa/Conakry"}""")
                .andExpect(status().is2xxSuccessful())
        }
        api.post(team, "/api/v1/billing/subscription/checkout", """{"plan":"Solo","months":1}""").andExpect(status().isBadRequest())
        pay(team, teamTenant, "/api/v1/billing/subscription/checkout", """{"plan":"Teams","months":1}""")
        api
            .get(team, "/api/v1/billing/subscription")
            .andExpect(jsonPath("$.plan").value("Teams"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `a failed or underpaid online payment grants nothing`() {
        val token = api.register()
        val tenantId = api.tenantId(token)

        val failed = checkout(token, "/api/v1/billing/pack/checkout", """{"months":1}""")
        callback("$tenantId:$failed", "FAILED")
        api.get(token, "/api/v1/billing/payments/$failed").andExpect(jsonPath("$.status").value("FAILED"))

        val underpaid = checkout(token, "/api/v1/billing/pack/checkout", """{"months":1}""")
        callback("$tenantId:$underpaid", "SUCCEEDED", amount = 1_000, currency = "GNF")
        api.get(token, "/api/v1/billing/payments/$underpaid").andExpect(jsonPath("$.status").value("FAILED"))

        api.get(token, "/api/v1/billing/pack").andExpect(jsonPath("$.active").value(false))
    }

    /** Checks out [path] and confirms it as the provider would; returns the payment id. */
    private fun pay(
        token: String,
        tenantId: String,
        path: String,
        json: String,
    ): String {
        val paymentId = checkout(token, path, json)
        callback("$tenantId:$paymentId", "SUCCEEDED")
        api.get(token, "/api/v1/billing/payments/$paymentId").andExpect(jsonPath("$.status").value("SUCCEEDED"))
        return paymentId
    }

    private fun checkout(
        token: String,
        path: String,
        json: String,
    ): String =
        JsonPath.read(
            api
                .post(token, path, json)
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.paymentId",
        )

    private fun callback(
        reference: String,
        outcome: String,
        amount: Long? = null,
        currency: String? = null,
    ) {
        val paid = if (amount != null) ""","amount":$amount,"currency":"$currency"""" else ""
        val body = """{"reference":"$reference","providerReference":"SANDBOX-x","status":"$outcome"$paid}"""
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", sign(body))
                    .content(body),
            ).andExpect(status().isOk())
    }

    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

private const val SECRET = "test-checkout-secret"
