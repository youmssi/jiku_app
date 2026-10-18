package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MetaErrors
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JIKU-269: an organizer checks a WhatsApp message while writing it, against
 * the same words the send blocks on, and a send refused for the account's
 * payment method says so in words.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WhatsAppMessageCheckTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `an informative message passes and a promotional one names its words`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()

        api
            .post(token, "/api/v1/messaging/whatsapp/check", """{"text":"Votre billet pour le mariage du 12 décembre est prêt."}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("UTILITY"))
            .andExpect(jsonPath("$.promotionalWords").isEmpty())
        api
            .post(token, "/api/v1/messaging/whatsapp/check", """{"text":"PROMO : -20 %, achetez maintenant ! Promo valable ce soir."}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.category").value("MARKETING"))
            .andExpect(jsonPath("$.promotionalWords.length()").value(2))
            .andExpect(jsonPath("$.promotionalWords[0]").value("promo"))
            .andExpect(jsonPath("$.promotionalWords[1]").value("achetez maintenant"))
    }

    @Test
    fun `checking needs a signed-in organizer and a bounded text`() {
        mockMvc
            .perform(post("/api/v1/messaging/whatsapp/check").contentType(MediaType.APPLICATION_JSON).content("""{"text":"x"}"""))
            .andExpect(status().is4xxClientError())
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        api
            .post(token, "/api/v1/messaging/whatsapp/check", """{"text":"${"a".repeat(4097)}"}""")
            .andExpect(status().isBadRequest())
    }

    @Test
    fun `a payment refusal from Meta is explained, other codes are not`() {
        assertTrue(MetaErrors.explain(MetaErrors.PAYMENT_ISSUE)!!.contains("payment method"))
        assertNull(MetaErrors.explain(131026))
        assertNull(MetaErrors.explain(null))
        assertEquals(131042, MetaErrors.PAYMENT_ISSUE)
    }
}
