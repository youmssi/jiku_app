package com.jiku.tenant

import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * JIKU-109: an organization lists the Mobile Money numbers and payment link its
 * clients pay it with; they travel with the organization's profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class PaymentMethodsTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `payment methods are saved, shown on the profile, and cleared when blank`() {
        val token = api.register()
        api
            .get(token, "/api/v1/settings/payment-methods")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orangeMoneyNumber").doesNotExist())

        api
            .put(
                token,
                "/api/v1/settings/payment-methods",
                """{"payeeName":" Clinique Nimba ","orangeMoneyNumber":"+224 620 00 00 00","paymentLinkUrl":"https://pay.example/nimba"}""",
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.payeeName").value("Clinique Nimba"))
            .andExpect(jsonPath("$.orangeMoneyNumber").value("+224 620 00 00 00"))
        api
            .get(token, "/api/v1/orgs/profile")
            .andExpect(jsonPath("$.paymentMethods.paymentLinkUrl").value("https://pay.example/nimba"))

        api
            .put(token, "/api/v1/settings/payment-methods", """{"orangeMoneyNumber":"  ","paymentLinkUrl":""}""")
            .andExpect(status().isOk())
        api
            .get(token, "/api/v1/orgs/profile")
            .andExpect(jsonPath("$.paymentMethods").doesNotExist())
    }

    @Test
    fun `a Mobile Money number needs the payee name the client will see`() {
        val token = api.register()

        api
            .put(token, "/api/v1/settings/payment-methods", """{"mtnMomoNumber":"+224 660 00 00 00"}""")
            .andExpect(status().isBadRequest())
    }

    @Test
    fun `a payment link must be secure`() {
        val token = api.register()

        api
            .put(token, "/api/v1/settings/payment-methods", """{"paymentLinkUrl":"http://pay.example/nimba"}""")
            .andExpect(status().isBadRequest())
    }
}
