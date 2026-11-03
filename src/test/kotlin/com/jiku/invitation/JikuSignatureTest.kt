package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.money.BillingModuleApi
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** JIKU-288: Jikū signs free cards only; a paid organizer's card carries its brand alone. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class JikuSignatureTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var billing: BillingModuleApi

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a free card is signed, and the signature leaves once Pro Organisateur covers the event`() {
        val token = api.register()
        val code = openCard(token)
        page(code).andExpect(jsonPath("$.jikuSignature").value(true))

        val instructions =
            api
                .post(token, "/api/v1/billing/pack/request", """{"months":1}""")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        billing.adminConfirmManualPayment(UUID.fromString(JsonPath.read(instructions, "$.paymentId")))

        page(code).andExpect(jsonPath("$.jikuSignature").value(false))
    }

    private fun openCard(token: String): String {
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """{"name":"Birthday","timezone":"Africa/Conakry","startDateTime":"${TestDates.EVENT_YEAR}-12-01T18:00:00Z",""" +
                            """"invitationChannels":["EMAIL"]}""",
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        return JsonPath.read(
            api
                .put(token, "/api/v1/events/$eventId/open-invitation", """{"maxCompanions":3}""")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.code",
        )
    }

    private fun page(code: String) =
        mockMvc
            .perform(
                get("/api/v1/open/$code").with {
                    it.remoteAddr = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
                    it
                },
            ).andExpect(status().isOk())
}
