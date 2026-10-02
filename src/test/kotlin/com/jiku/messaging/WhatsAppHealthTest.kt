package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MetaWebhookSignature
import com.jiku.messaging.internal.WhatsAppHealthService
import com.jiku.messaging.internal.WhatsAppUnavailableException
import com.jiku.shared.OpsAlert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest(properties = ["jiku.whatsapp.meta.app-secret=$HEALTH_SECRET"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class WhatsAppHealthTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var health: WhatsAppHealthService

    @Autowired
    lateinit var events: ApplicationEvents

    private val waba = "WABA-${UUID.randomUUID()}"

    @Test
    fun `a paused template is not used until Meta approves it again, and the team is alerted once`() {
        templateStatus("PAUSED")
        templateStatus("PAUSED")

        assertThatThrownBy { health.assertUsable(waba, "jiku_invitation", "fr") }
            .isInstanceOf(WhatsAppUnavailableException::class.java)
        assertThatCode { health.assertUsable(waba, "jiku_invitation", "en") }.doesNotThrowAnyException()
        assertThatCode { health.assertUsable("OTHER", "jiku_invitation", "fr") }.doesNotThrowAnyException()
        assertThat(alerts("jiku_invitation (fr) is PAUSED")).isEqualTo(1)

        templateStatus("REINSTATED")

        assertThatCode { health.assertUsable(waba, "jiku_invitation", "fr") }.doesNotThrowAnyException()
    }

    @Test
    fun `a template moved to marketing is not used until Meta files it as utility again`() {
        category("MARKETING")

        assertThatThrownBy { health.assertUsable(waba, "jiku_ticket", "fr") }.isInstanceOf(WhatsAppUnavailableException::class.java)
        assertThat(alerts("jiku_ticket (fr) moved to MARKETING")).isEqualTo(1)

        category("UTILITY")

        assertThatCode { health.assertUsable(waba, "jiku_ticket", "fr") }.doesNotThrowAnyException()
    }

    @Test
    fun `a utility category does not lift a pause`() {
        templateStatus("PAUSED")
        category("UTILITY")

        assertThatThrownBy { health.assertUsable(waba, "jiku_invitation", "fr") }
            .isInstanceOf(WhatsAppUnavailableException::class.java)
    }

    @Test
    fun `a paused template Meta refuses at send time is no longer tried`() {
        health.onSendRefused(waba, "jiku_reminder", "fr", 132015)

        assertThatThrownBy { health.assertUsable(waba, "jiku_reminder", "fr") }.isInstanceOf(WhatsAppUnavailableException::class.java)
    }

    @Test
    fun `quality drops, flagged numbers and account restrictions alert the team`() {
        change(
            "message_template_quality_update",
            """{"new_quality_score":"RED","message_template_name":"jiku_invitation","message_template_language":"fr"}""",
        )
        change("phone_number_quality_update", """{"display_phone_number":"224620000000","event":"FLAGGED"}""")
        change("phone_number_quality_update", """{"display_phone_number":"224620000000","event":"UPGRADE"}""")
        change("account_update", """{"event":"ACCOUNT_RESTRICTION"}""")

        assertThat(alerts("quality is RED")).isEqualTo(1)
        assertThat(alerts("224620000000 is FLAGGED")).isEqualTo(1)
        assertThat(alerts("224620000000 is UPGRADE")).isEqualTo(0)
        assertThat(alerts("$waba: ACCOUNT_RESTRICTION")).isEqualTo(1)
    }

    @Test
    fun `an unsigned account event is refused`() {
        val body = body("account_update", """{"event":"ACCOUNT_RESTRICTION"}""")
        mockMvc
            .perform(post("/api/v1/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized())
    }

    private fun templateStatus(event: String) =
        change(
            "message_template_status_update",
            """{"event":"$event","message_template_name":"jiku_invitation","message_template_language":"fr","reason":"LOW_QUALITY"}""",
        )

    private fun category(category: String) =
        change(
            "template_category_update",
            """{"message_template_name":"jiku_ticket","message_template_language":"fr","new_category":"$category"}""",
        )

    private fun change(
        field: String,
        value: String,
    ) {
        val body = body(field, value)
        mockMvc
            .perform(
                post("/api/v1/whatsapp/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body)
                    .header("X-Hub-Signature-256", "sha256=" + MetaWebhookSignature.sign(HEALTH_SECRET, body)),
            ).andExpect(status().isOk())
    }

    private fun body(
        field: String,
        value: String,
    ) = """{"object":"whatsapp_business_account","entry":[{"id":"$waba","changes":[{"field":"$field","value":$value}]}]}"""

    private fun alerts(fragment: String): Long =
        events.stream(OpsAlert::class.java).filter { waba in it.subject + it.message && fragment in it.subject }.count()
}

private const val HEALTH_SECRET = "health-app-secret"
