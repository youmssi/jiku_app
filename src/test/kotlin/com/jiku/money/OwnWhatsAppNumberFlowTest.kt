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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The organization's own WhatsApp number (ADR 105): included in the
 * Organisation plan and the Organizer Pack, a monthly add-on otherwise, with
 * a year charged as ten months.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OwnWhatsAppNumberFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var billingModuleApi: BillingModuleApi

    private lateinit var api: OrganizerApi

    @BeforeEach
    fun setUp() {
        api = OrganizerApi(mockMvc)
    }

    @Test
    fun `a free plan buys the own number as a monthly add-on`() {
        val token = api.register()
        api
            .get(token, "/api/v1/billing/whatsapp-number")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(jsonPath("$.source").value("NONE"))
            .andExpect(jsonPath("$.monthlyMinor").value(100_000))
            .andExpect(jsonPath("$.includedPlans[0]").value("Organisation"))

        request(token, """{"months":12}""", 1_000_000)
        confirm(request(token, """{"months":1}""", 100_000))

        api
            .get(token, "/api/v1/billing/whatsapp-number")
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.source").value("ADDON"))
            .andExpect(jsonPath("$.addonExpiresAt").isNotEmpty())
        api
            .get(token, "/api/v1/billing/payments")
            .andExpect(jsonPath("$[?(@.eventName == 'Own WhatsApp number (1 months)')].status").value("SUCCEEDED"))
    }

    @Test
    fun `the Organisation plan and the Organizer Pack include the own number`() {
        val organisation = api.register()
        api
            .post(organisation, "/api/v1/resources", """{"name":"Accueil","type":"PERSON","timezone":"Africa/Conakry"}""")
            .andExpect(status().is2xxSuccessful())
        confirm(
            api
                .post(organisation, "/api/v1/billing/subscription/request", """{"plan":"Organisation","months":1}""")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
        )
        api
            .get(organisation, "/api/v1/billing/whatsapp-number")
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.source").value("PLAN"))
        api.post(organisation, "/api/v1/billing/whatsapp-number/request", """{"months":1}""").andExpect(status().isConflict())

        val planner = api.register()
        confirm(
            api
                .post(planner, "/api/v1/billing/pack/request", """{"months":1}""")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
        )
        api
            .get(planner, "/api/v1/billing/whatsapp-number")
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.source").value("PACK"))
    }

    private fun request(
        token: String,
        body: String,
        expectedAmount: Int,
    ): String {
        val response =
            api
                .post(token, "/api/v1/billing/whatsapp-number/request", body)
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assertEquals(expectedAmount, JsonPath.read<Int>(response, "$.amountMinor"))
        return response
    }

    private fun confirm(instructions: String) {
        billingModuleApi.adminConfirmManualPayment(UUID.fromString(JsonPath.read(instructions, "$.paymentId")))
    }
}
