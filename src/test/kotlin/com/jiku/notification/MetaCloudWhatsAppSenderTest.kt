package com.jiku.notification

import com.jiku.notification.internal.MetaCloudWhatsAppSender
import com.jiku.notification.internal.WhatsAppDeliveryException
import com.jiku.notification.internal.WhatsAppMessage
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

class MetaCloudWhatsAppSenderTest {
    private val message = WhatsAppMessage(to = "+2250700000001", body = "Vous êtes invité")

    private fun mockedSender(
        templateName: String?,
        expectations: (MockRestServiceServer) -> Unit,
    ): MetaCloudWhatsAppSender {
        val builder = RestClient.builder().baseUrl("https://graph.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        expectations(server)
        return MetaCloudWhatsAppSender(
            client = builder.build(),
            phoneNumberId = "123456789",
            templateName = templateName,
            templateLanguage = "fr",
        )
    }

    @Test
    fun `sends a plain text message when no template is configured`() {
        val sender =
            mockedSender(templateName = null) { server ->
                server
                    .expect(requestTo("https://graph.test/123456789/messages"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect { request ->
                        val body = (request as MockClientHttpRequest).bodyAsString
                        assertTrue(body.contains("\"messaging_product\":\"whatsapp\""))
                        assertTrue(body.contains("\"to\":\"+2250700000001\""))
                        assertTrue(body.contains("\"type\":\"text\""))
                    }.andRespond(withSuccess("""{"messages":[{"id":"wamid.1"}]}""", MediaType.APPLICATION_JSON))
            }
        sender.send(message)
    }

    @Test
    fun `sends an approved template with the rendered text as body parameter`() {
        val sender =
            mockedSender(templateName = "jiku_invitation") { server ->
                server
                    .expect(requestTo("https://graph.test/123456789/messages"))
                    .andExpect { request ->
                        val body = (request as MockClientHttpRequest).bodyAsString
                        assertTrue(body.contains("\"type\":\"template\""))
                        assertTrue(body.contains("\"name\":\"jiku_invitation\""))
                        assertTrue(body.contains("\"code\":\"fr\""))
                        assertTrue(body.contains("Vous êtes invité"))
                    }.andRespond(withSuccess("""{"messages":[{"id":"wamid.2"}]}""", MediaType.APPLICATION_JSON))
            }
        sender.send(message)
    }

    @Test
    fun `wraps a provider rejection so the orchestration retry engages`() {
        val sender =
            mockedSender(templateName = null) { server ->
                server
                    .expect(requestTo("https://graph.test/123456789/messages"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("""{"error":{"message":"invalid token"}}"""))
            }
        assertFailsWith<WhatsAppDeliveryException> { sender.send(message) }
    }

    @Test
    fun `fails fast at wiring time when credentials are missing`() {
        assertFailsWith<IllegalStateException> {
            MetaCloudWhatsAppSender.build(
                builder = RestClient.builder(),
                accessToken = "",
                phoneNumberId = "123",
                baseUrl = "https://graph.test",
                templateName = null,
                templateLanguage = "fr",
            )
        }
    }
}
