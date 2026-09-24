package com.jiku.messaging

import com.jiku.messaging.internal.NimbaSmsSender
import com.jiku.messaging.internal.SmsDeliveryException
import com.jiku.messaging.internal.SmsMessage
import com.jiku.messaging.internal.SmsProperties
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.Base64
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** JIKU-112: an SMS goes to Nimba under the approved sender name, with Basic credentials. */
class NimbaSmsSenderTest {
    private val properties =
        SmsProperties.Nimba(serviceId = "svc", secretToken = "secret", senderName = "Jiku", baseUrl = "https://nimba.test")
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val sender = NimbaSmsSender.build(builder, properties)

    @Test
    fun `posts the message under the sender name with Basic credentials`() {
        val basic = Base64.getEncoder().encodeToString("svc:secret".toByteArray())
        server
            .expect(requestTo("https://nimba.test/v1/messages"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic $basic"))
            .andExpect { request ->
                val body = (request as MockClientHttpRequest).bodyAsString
                assertTrue(body.contains("\"to\":[\"+224620000000\"]"))
                assertTrue(body.contains("\"sender_name\":\"Jiku\""))
                assertTrue(body.contains("\"message\":\"Rappel\""))
            }.andRespond(withSuccess())

        sender.send(SmsMessage(to = "+224 620 00 00 00", body = "Rappel"))
        server.verify()
    }

    @Test
    fun `a rejection engages the orchestration retry`() {
        server.expect(requestTo("https://nimba.test/v1/messages")).andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED))

        assertFailsWith<SmsDeliveryException> { sender.send(SmsMessage(to = "+224620000000", body = "Rappel")) }
    }

    @Test
    fun `a missing sender name stops startup`() {
        assertFailsWith<IllegalStateException> {
            NimbaSmsSender.build(RestClient.builder(), properties.copy(senderName = ""))
        }
    }
}
