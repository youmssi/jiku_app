package com.jiku.messaging

import com.jiku.messaging.internal.EmailDeliveryException
import com.jiku.messaging.internal.EmailMessage
import com.jiku.messaging.internal.ResendEmailSender
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResendEmailSenderTest {
    private val message =
        EmailMessage(
            to = "awa@example.com",
            toName = "Awa Diop",
            subject = "Invitation",
            htmlBody = "<p>Bienvenue</p>",
        )

    @Test
    fun `posts the rendered message to the Resend emails endpoint`() {
        val builder = RestClient.builder().baseUrl("https://resend.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo("https://resend.test/emails"))
            .andExpect(method(HttpMethod.POST))
            .andExpect { request ->
                val body = (request as MockClientHttpRequest).bodyAsString
                assertTrue(body.contains("\"from\":\"no-reply@jiku.app\""))
                assertTrue(body.contains("Awa Diop <awa@example.com>"))
                assertTrue(body.contains("\"subject\":\"Invitation\""))
            }.andRespond(withSuccess("""{"id":"email_1"}""", MediaType.APPLICATION_JSON))

        ResendEmailSender(builder.build()).send("no-reply@jiku.app", message)
        server.verify()
    }

    @Test
    fun `wraps a provider rejection so the orchestration retry engages`() {
        val builder = RestClient.builder().baseUrl("https://resend.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo("https://resend.test/emails"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).body("""{"message":"invalid from"}"""))

        assertFailsWith<EmailDeliveryException> {
            ResendEmailSender(builder.build()).send("no-reply@jiku.app", message)
        }
    }

    @Test
    fun `fails fast at wiring time when the API key is missing`() {
        assertFailsWith<IllegalStateException> {
            ResendEmailSender.buildClient(RestClient.builder(), "", "https://resend.test")
        }
    }
}
