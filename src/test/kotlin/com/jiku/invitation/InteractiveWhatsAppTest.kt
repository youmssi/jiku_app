package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MetaWebhookSignature
import com.jiku.money.BillingModuleApi
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates.EVENT_YEAR
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * JIKU-143: an interactive event's WhatsApp invitation carries accept and
 * decline buttons; a tap from the invited number confirms the guest and sends
 * the ticket back in the chat with its QR code; typed keywords resend it, and
 * STOP silences the number until START.
 */
@SpringBootTest(properties = ["jiku.whatsapp.meta.app-secret=$APP_SECRET", "jiku.whatsapp.meta.verify-token=$VERIFY_TOKEN"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class InteractiveWhatsAppTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var billing: BillingModuleApi

    @Test
    fun `a guest accepts in the chat, gets the ticket there and can ask for it again`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId = interactiveEvent(api, token)
        val phone = "+22462" + Random.nextInt(1_000_000, 9_999_999)
        val digits = phone.removePrefix("+")
        val guestId = importGuest(api, token, eventId, phone)

        api.post(token, "/api/v1/events/$eventId/invitations/send?channels=WHATSAPP", "{}").andExpect(status().isOk())
        val invitationId =
            await()
                .atMost(
                    10,
                    TimeUnit.SECONDS,
                ).until({ BUTTONS.find(output.out.substringAfter("to=$phone buttons=[RSVP"))?.groupValues?.get(1) }, {
                    it !=
                        null
                })!!

        webhook(button = "RSVP_YES:$invitationId", from = digits, signed = false).andExpect(status().isUnauthorized())

        webhook(button = "RSVP_YES:$invitationId", from = digits).andExpect(status().isOk())
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            api.get(token, "/api/v1/events/$eventId/guests").andExpect(jsonPath("$[?(@.id == '$guestId')].rsvpStatus").value("CONFIRMED"))
            assert(tickets(output, phone) == 1) { "the ticket was not sent back in the chat" }
        }
        val qrPath = QR.find(output.out)!!.groupValues[1]
        mockMvc.perform(get(qrPath)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))

        webhook(button = "RSVP_NO:$invitationId", from = "224600000000").andExpect(status().isOk())
        await().during(Duration.ofSeconds(1)).atMost(3, TimeUnit.SECONDS).untilAsserted {
            api.get(token, "/api/v1/events/$eventId/guests").andExpect(jsonPath("$[?(@.id == '$guestId')].rsvpStatus").value("CONFIRMED"))
        }

        webhook(text = "  Billet ", from = digits).andExpect(status().isOk())
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assert(tickets(output, phone) == 2) { "the ticket was not sent again" } }

        webhook(text = "STOP", from = digits).andExpect(status().isOk())
        webhook(text = "billet", from = digits).andExpect(status().isOk())
        await().during(Duration.ofSeconds(1)).atMost(3, TimeUnit.SECONDS).untilAsserted {
            assert(tickets(output, phone) == 2) { "a number that wrote STOP still got a ticket" }
        }

        webhook(text = "Start", from = digits).andExpect(status().isOk())
        webhook(text = "ticket", from = digits).andExpect(status().isOk())
        await().atMost(10, TimeUnit.SECONDS).untilAsserted { assert(tickets(output, phone) == 3) { "START did not lift the silence" } }
    }

    @Test
    fun `a paid event without the interactive surcharge sends a plain link`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId = api.createEvent(token)
        val payment =
            api
                .post(token, "/api/v1/events/$eventId/payments/manual", """{"tier":"BRONZE"}""")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        billing.adminConfirmManualPayment(UUID.fromString(JsonPath.read(payment, "$.paymentId")))
        api
            .put(
                token,
                "/api/v1/events/$eventId",
                """
                {"name":"Gala sans supplément","timezone":"Africa/Conakry","startDateTime":"${EVENT_YEAR}-12-01T19:00:00Z",
                "invitationChannels":["WHATSAPP"],"settings":{"deliveryMode":"INTERACTIVE"}}
                """.trimIndent(),
            ).andExpect(status().isOk())
        api.publish(token, eventId)
        val phone = "+22463" + Random.nextInt(1_000_000, 9_999_999)
        importGuest(api, token, eventId, phone)

        api.post(token, "/api/v1/events/$eventId/invitations/send?channels=WHATSAPP", "{}").andExpect(status().isOk())

        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assert(output.out.contains("to=$phone buttons=[] image=null")) { "the invitation was not sent as a plain link" }
        }
    }

    @Test
    fun `Meta's registration check needs the verify token`() {
        mockMvc
            .perform(get("/api/v1/whatsapp/webhook?hub.mode=subscribe&hub.verify_token=$VERIFY_TOKEN&hub.challenge=42"))
            .andExpect(status().isOk())
            .andExpect(content().string("42"))
        mockMvc
            .perform(get("/api/v1/whatsapp/webhook?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=42"))
            .andExpect(status().isForbidden())
    }

    private fun interactiveEvent(
        api: OrganizerApi,
        token: String,
    ): String {
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """
                        {"name":"Gala interactif","timezone":"Africa/Conakry","startDateTime":"${EVENT_YEAR}-12-01T19:00:00Z",
                        "invitationChannels":["WHATSAPP"],"settings":{"deliveryMode":"INTERACTIVE"}}
                        """.trimIndent(),
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        return eventId
    }

    private fun importGuest(
        api: OrganizerApi,
        token: String,
        eventId: String,
        phone: String,
    ): String {
        val csv = "firstName,lastName,email,phone\nMariama,Test,,$phone"
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
        val guests =
            api
                .get(token, "/api/v1/events/$eventId/guests")
                .andReturn()
                .response.contentAsString
        return JsonPath.read<List<String>>(guests, "$[?(@.firstName=='Mariama')].id").first()
    }

    private fun webhook(
        from: String,
        button: String? = null,
        text: String? = null,
        signed: Boolean = true,
    ): ResultActions {
        val message =
            if (button != null) {
                """{"from":"$from","type":"button","button":{"payload":"$button","text":"ok"}}"""
            } else {
                """{"from":"$from","type":"text","text":{"body":"${text.orEmpty()}"}}"""
            }
        val body = """{"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{"messages":[$message]}}]}]}"""
        val request = post("/api/v1/whatsapp/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
        if (signed) request.header("X-Hub-Signature-256", "sha256=" + MetaWebhookSignature.sign(APP_SECRET, body))
        return mockMvc.perform(request)
    }

    private fun tickets(
        output: CapturedOutput,
        phone: String,
    ): Int = Regex(Regex.escape("to=$phone buttons=[] image=http")).findAll(output.out).count()

    private companion object {
        val BUTTONS = Regex("^_YES:([0-9a-f-]{36})")
        val QR = Regex("image=http://localhost:\\d+(/api/v1/rsvp/[^/\\s]+/qr\\.png)")
    }
}

private const val APP_SECRET = "test-app-secret"
private const val VERIFY_TOKEN = "test-verify-token"
