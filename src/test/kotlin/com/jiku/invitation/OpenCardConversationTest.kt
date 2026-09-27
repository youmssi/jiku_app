package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MetaWebhookSignature
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * JIKU-185, ADR 106: an open invitation answered without leaving WhatsApp. The
 * card's link opens a chat with the number dedicated to cards; the code is
 * answered with buttons, a yes asks for companions, and the ticket comes back
 * in the chat. The name is the one shown in WhatsApp, the number the sender's.
 */
@SpringBootTest(
    properties = [
        "jiku.whatsapp.meta.app-secret=$CARDS_APP_SECRET",
        "jiku.whatsapp.meta.cards-phone-number-id=$CARDS_NUMBER_ID",
        "open-invitation.whatsapp-number=224600000099",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class OpenCardConversationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a person answers in the chat, says who comes along, and gets the ticket there`(output: CapturedOutput) {
        val open = openInvitation(maxCompanions = 5)
        mockMvc
            .perform(post("/api/v1/open/${open.code}/responses").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
        api
            .get(open.token, "/api/v1/events/${open.eventId}/open-invitation")
            .andExpect(jsonPath("$.whatsappNumber").value("224600000099"))

        val phone = randomDigits()
        webhook(from = phone, name = "Awa Diallo", text = "Bonjour, je réponds à l'invitation ${open.code}").andExpect(status().isOk())
        awaitSent(
            output,
            "to=+$phone buttons=[OI:Y:${open.code}, OI:M:${open.code}, OI:N:${open.code}] image=http://localhost:3000/api/cards/${open.code}",
        )

        webhook(from = phone, name = "Awa Diallo", button = "OI:Y:${open.code}").andExpect(status().isOk())
        awaitSent(output, "to=+$phone buttons=[OI:C0:${open.code}, OI:C1:${open.code}, OI:C2:${open.code}] image=null")
        api
            .get(open.token, "/api/v1/events/${open.eventId}/open-invitation")
            .andExpect(jsonPath("$.counts.yes").value(1))
            .andExpect(jsonPath("$.counts.expected").value(1))

        webhook(from = phone, name = "Awa Diallo", text = "4").andExpect(status().isOk())
        awaitSent(output, "to=+$phone buttons=[] image=http")
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            api
                .get(open.token, "/api/v1/events/${open.eventId}/open-invitation/responses")
                .andExpect(jsonPath("$[0].name").value("Awa Diallo"))
                .andExpect(jsonPath("$[0].phone").value("+$phone"))
                .andExpect(jsonPath("$[0].channel").value("WHATSAPP"))
                .andExpect(jsonPath("$[0].companions").value(4))
        }
    }

    @Test
    fun `maybe and no are recorded, and a number that wrote STOP gets nothing more`(output: CapturedOutput) {
        val open = openInvitation(maxCompanions = 2)
        val phone = randomDigits()
        webhook(from = phone, name = "Moussa", button = "OI:M:${open.code}").andExpect(status().isOk())
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            api
                .get(open.token, "/api/v1/events/${open.eventId}/open-invitation")
                .andExpect(jsonPath("$.counts.maybe").value(1))
        }
        awaitSent(output, "to=+$phone buttons=[] image=null")

        webhook(from = phone, name = "Moussa", text = "STOP").andExpect(status().isOk())
        val before = sent(output, phone)
        webhook(from = phone, name = "Moussa", button = "OI:N:${open.code}").andExpect(status().isOk())
        await().during(Duration.ofSeconds(1)).atMost(3, TimeUnit.SECONDS).untilAsserted {
            assert(sent(output, phone) == before) { "a number that wrote STOP still got an answer" }
        }
    }

    @Test
    fun `an unknown code is told so, and messages to another number are not a card conversation`(output: CapturedOutput) {
        val phone = randomDigits()
        webhook(from = phone, name = "X", text = "ZZZZ2345").andExpect(status().isOk())
        awaitSent(output, "to=+$phone buttons=[] image=null")

        val open = openInvitation(maxCompanions = 2)
        val other = randomDigits()
        webhook(from = other, name = "Y", button = "OI:Y:${open.code}", numberId = "SOME-OTHER-NUMBER").andExpect(status().isOk())
        await().during(Duration.ofSeconds(1)).atMost(3, TimeUnit.SECONDS).untilAsserted {
            api
                .get(open.token, "/api/v1/events/${open.eventId}/open-invitation")
                .andExpect(jsonPath("$.counts.yes").value(0))
        }
    }

    private data class Open(
        val token: String,
        val eventId: String,
        val code: String,
    )

    private fun openInvitation(maxCompanions: Int): Open {
        val token = api.register()
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """{"name":"Birthday","timezone":"Africa/Conakry","startDateTime":"${TestDates.EVENT_YEAR}-12-01T18:00:00Z",""" +
                            """"location":"Kaloum","invitationChannels":["EMAIL"],"maxCapacity":50}""",
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        val code =
            JsonPath.read<String>(
                api
                    .put(token, "/api/v1/events/$eventId/open-invitation", """{"maxCompanions":$maxCompanions}""")
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )
        return Open(token, eventId, code)
    }

    private fun webhook(
        from: String,
        name: String,
        button: String? = null,
        text: String? = null,
        numberId: String = CARDS_NUMBER_ID,
    ): ResultActions {
        val message =
            if (button != null) {
                """{"from":"$from","type":"interactive","interactive":{"type":"button_reply","button_reply":{"id":"$button","title":"ok"}}}"""
            } else {
                """{"from":"$from","type":"text","text":{"body":"${text.orEmpty()}"}}"""
            }
        val value =
            """{"metadata":{"phone_number_id":"$numberId"},"contacts":[{"wa_id":"$from","profile":{"name":"$name"}}],"messages":[$message]}"""
        val body = """{"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":$value}]}]}"""
        return mockMvc.perform(
            post("/api/v1/whatsapp/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Hub-Signature-256", "sha256=" + MetaWebhookSignature.sign(CARDS_APP_SECRET, body)),
        )
    }

    private fun awaitSent(
        output: CapturedOutput,
        line: String,
    ) = await().atMost(10, TimeUnit.SECONDS).untilAsserted { assert(output.out.contains(line)) { "not sent: $line" } }

    private fun sent(
        output: CapturedOutput,
        phone: String,
    ): Int = Regex(Regex.escape("to=+$phone ")).findAll(output.out).count()

    private fun randomDigits(): String = "22462" + Random.nextInt(1_000_000, 9_999_999)
}

private const val CARDS_APP_SECRET = "cards-app-secret"
private const val CARDS_NUMBER_ID = "CARDS-NUMBER-ID"
