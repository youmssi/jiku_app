package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals

private const val INVOICE_SECRET = "test-invoice-secret"

/**
 * JIKU-277: a confirmed payment issues its invoice on its own and emails it with
 * the PDF, once even when the confirmation is replayed; without a legal identity
 * the invoice waits and is issued the moment the identity is completed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["billing.payment.webhook-secret=$INVOICE_SECRET"])
@ExtendWith(OutputCaptureExtension::class)
class AutomaticInvoiceFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a confirmed payment issues and emails its invoice once`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        fillLegalIdentity(api, token)
        val tenantId = api.tenantId(token)
        val paymentId = checkoutPack(api, token)

        callback("$tenantId:$paymentId")
        callback("$tenantId:$paymentId")

        val number = awaitInvoices(api, token, 1).single()
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assert(output.out.contains("subject=\"Votre facture $number\" attachments=[$number.pdf]")) { "the invoice was not emailed" }
        }
        Thread.sleep(1_000)
        assertEquals(1, invoiceNumbers(api, token).size)
        assertEquals(1, output.out.split("subject=\"Votre facture $number\"").size - 1)
    }

    @Test
    fun `without a legal identity the invoice waits for it`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val tenantId = api.tenantId(token)
        val paymentId = checkoutPack(api, token)

        callback("$tenantId:$paymentId")
        Thread.sleep(1_500)
        assertEquals(0, invoiceNumbers(api, token).size)

        fillLegalIdentity(api, token)

        val number = awaitInvoices(api, token, 1).single()
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assert(output.out.contains("subject=\"Votre facture $number\" attachments=[$number.pdf]")) { "the invoice was not emailed" }
        }
    }

    private fun fillLegalIdentity(
        api: OrganizerApi,
        token: String,
    ) {
        api
            .put(
                token,
                "/api/v1/legal-identity",
                """{"legalName":"Societe Auto SARL","addressLine":"12 rue du Commerce","city":"Conakry","country":"GN"}""",
            ).andExpect(status().isOk())
    }

    private fun checkoutPack(
        api: OrganizerApi,
        token: String,
    ): String =
        JsonPath.read(
            api
                .post(token, "/api/v1/billing/pack/checkout", """{"months":1}""")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.paymentId",
        )

    private fun awaitInvoices(
        api: OrganizerApi,
        token: String,
        count: Int,
    ): List<String> {
        var numbers = emptyList<String>()
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            numbers = invoiceNumbers(api, token)
            assertEquals(count, numbers.size)
        }
        return numbers
    }

    private fun invoiceNumbers(
        api: OrganizerApi,
        token: String,
    ): List<String> =
        JsonPath.read(
            api
                .get(token, "/api/v1/billing/invoices")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$[*].invoiceNumber",
        )

    private fun callback(reference: String) {
        val body = """{"reference":"$reference","providerReference":"SANDBOX-x","status":"SUCCEEDED","amount":600000,"currency":"GNF"}"""
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
        mac.init(SecretKeySpec(INVOICE_SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
