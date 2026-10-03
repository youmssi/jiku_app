package com.jiku.messaging

import com.jiku.messaging.internal.EmailAttachment
import com.jiku.messaging.internal.EmailDeliveryException
import com.jiku.messaging.internal.EmailMessage
import com.jiku.messaging.internal.UseSendEmailSender
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.Base64
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UseSendEmailSenderTest {
    private val message =
        EmailMessage(
            to = "awa@example.com",
            toName = "Awa Diop",
            subject = "Invitation",
            htmlBody = "<p>Bienvenue</p>",
            attachments = listOf(EmailAttachment("billet.ics", "text/calendar", "BEGIN:VCALENDAR".toByteArray())),
        )

    @Test
    fun `posts the rendered message and its attachment to the useSend emails endpoint`() {
        val builder = RestClient.builder().baseUrl("https://usesend.test/api")
        val server = MockRestServiceServer.bindTo(builder).build()
        val encoded = Base64.getEncoder().encodeToString("BEGIN:VCALENDAR".toByteArray())
        server
            .expect(requestTo("https://usesend.test/api/v1/emails"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request ->
                val body = (request as MockClientHttpRequest).bodyAsString
                assertTrue(body.contains("\"from\":\"no-reply@jiku.app\""))
                assertTrue(body.contains("\"to\":\"Awa Diop <awa@example.com>\""))
                assertTrue(body.contains("\"subject\":\"Invitation\""))
                assertTrue(body.contains("\"html\":\"<p>Bienvenue</p>\""))
                assertTrue(body.contains("{\"filename\":\"billet.ics\",\"content\":\"$encoded\"}"))
            }.andRespond(withSuccess("""{"emailId":"email_1"}""", MediaType.APPLICATION_JSON))

        UseSendEmailSender(builder.build()).send("no-reply@jiku.app", message)
        server.verify()
    }

    @Test
    fun `sends to the bare address when the recipient has no name`() {
        val builder = RestClient.builder().baseUrl("https://usesend.test/api")
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo("https://usesend.test/api/v1/emails"))
            .andExpect { request ->
                assertTrue((request as MockClientHttpRequest).bodyAsString.contains("\"to\":\"awa@example.com\""))
            }.andRespond(withSuccess("""{"emailId":"email_2"}""", MediaType.APPLICATION_JSON))

        UseSendEmailSender(builder.build()).send("no-reply@jiku.app", message.copy(toName = ""))
        server.verify()
    }

    @Test
    fun `wraps a provider rejection so the orchestration retry engages`() {
        val builder = RestClient.builder().baseUrl("https://usesend.test/api")
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo("https://usesend.test/api/v1/emails"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("""{"error":"domain not verified"}"""))

        assertFailsWith<EmailDeliveryException> {
            UseSendEmailSender(builder.build()).send("no-reply@jiku.app", message)
        }
    }

    @Test
    fun `wraps a server error so the orchestration retry engages`() {
        val builder = RestClient.builder().baseUrl("https://usesend.test/api")
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo("https://usesend.test/api/v1/emails"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

        assertFailsWith<EmailDeliveryException> {
            UseSendEmailSender(builder.build()).send("no-reply@jiku.app", message)
        }
    }

    @Test
    fun `fails fast at wiring time when the API key is missing`() {
        assertFailsWith<IllegalStateException> {
            UseSendEmailSender.buildClient(RestClient.builder(), "", "https://usesend.test/api")
        }
    }
}
