package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MetaWebhookSignature
import com.jiku.money.internal.OwnWhatsAppNumberService
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val APP_ID = "app-777"
private const val APP_SECRET = "embedded-secret"
private const val SIGNUP = "/api/v1/settings/providers/whatsapp/embedded-signup"

/**
 * Embedded Signup (ADR 105) against a stand-in for Meta's Graph API: the
 * signup code becomes the organization's token, Jikū subscribes to the
 * account, registers the number, creates its templates there and saves the
 * number as the organization's WhatsApp provider. A guest who writes to that
 * number is answered from it.
 */
@SpringBootTest(
    properties = [
        "jiku.whatsapp.meta.app-id=$APP_ID",
        "jiku.whatsapp.meta.embedded-signup-config-id=cfg-42",
        "jiku.whatsapp.meta.app-secret=$APP_SECRET",
        "jiku.whatsapp.meta.template-name=jiku_message",
        "jiku.whatsapp.meta.buttons-template-name=jiku_invitation",
        "jiku.whatsapp.meta.image-template-name=jiku_ticket",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EmbeddedSignupTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var ownNumber: OwnWhatsAppNumberService

    private lateinit var api: OrganizerApi

    @BeforeEach
    fun setUp() {
        api = OrganizerApi(mockMvc)
        calls.clear()
    }

    @AfterEach
    fun clearTenant() {
        TenantContext.clear()
    }

    @Test
    fun `the signup window's code connects the organization's own number`() {
        val token = api.register()
        api
            .get(token, "/api/v1/settings/providers/whatsapp/embedded-signup")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.appId").value(APP_ID))
            .andExpect(jsonPath("$.configId").value("cfg-42"))
        val phoneNumberId = numberId()
        val body = """{"code":"good-code","wabaId":"waba-1","phoneNumberId":"$phoneNumberId"}"""
        api.post(token, SIGNUP, body).andExpect(status().isPaymentRequired())
        assertTrue(calls.isEmpty(), "nothing reaches Meta without an offer that includes the number")

        allowOwnNumber(token)
        api
            .post(token, SIGNUP, body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.whatsapp.configured").value(true))
            .andExpect(jsonPath("$.whatsapp.provider").value("META_EMBEDDED"))
            .andExpect(jsonPath("$.whatsapp.phoneNumberId").value(phoneNumberId))
            .andExpect(jsonPath("$.whatsapp.displayPhoneNumber").value("+224 620 00 00 00"))
            .andExpect(jsonPath("$.whatsapp.verifiedName").value("Salle Kaloum"))
            .andExpect(jsonPath("$.whatsapp.accessTokenMasked").value("••••OKEN"))

        assertTrue(calls.any { it.path == "/oauth/access_token" && "code=good-code" in it.query && "client_id=$APP_ID" in it.query })
        assertTrue(calls.any { it.method == "POST" && it.path == "/waba-1/subscribed_apps" && it.auth == "Bearer BIZTOKEN" })
        val register = calls.single { it.path == "/$phoneNumberId/register" }
        assertTrue(Regex("\"pin\":\"\\d{6}\"").containsMatchIn(register.body), register.body)
        val templates = calls.filter { it.path == "/waba-1/message_templates" }
        assertEquals(
            listOf("jiku_message", "jiku_invitation", "jiku_ticket"),
            templates.map {
                Regex("\"name\":\"(\\w+)\"").find(it.body)!!.groupValues[1]
            },
        )
        assertTrue("\"header_handle\":[\"HANDLE-1\"]" in templates.last().body, templates.last().body)
    }

    @Test
    fun `a refused code or a number another organization holds connects nothing`() {
        val token = api.register()
        allowOwnNumber(token)
        val phoneNumberId = numberId()
        api
            .post(token, SIGNUP, """{"code":"bad-code","wabaId":"waba-2","phoneNumberId":"$phoneNumberId"}""")
            .andExpect(status().isBadGateway())
        api.get(token, "/api/v1/settings/providers").andExpect(jsonPath("$.whatsapp.configured").value(false))

        api.post(token, SIGNUP, """{"code":"good-code","wabaId":"waba-2","phoneNumberId":"$phoneNumberId"}""").andExpect(status().isOk())
        val other = api.register()
        allowOwnNumber(other)
        api
            .post(other, SIGNUP, """{"code":"good-code","wabaId":"waba-3","phoneNumberId":"$phoneNumberId"}""")
            .andExpect(status().isConflict())
    }

    @Test
    fun `a guest who writes to the organization's number is answered from it`() {
        val token = api.register()
        allowOwnNumber(token)
        val phoneNumberId = numberId()
        api.post(token, SIGNUP, """{"code":"good-code","wabaId":"waba-4","phoneNumberId":"$phoneNumberId"}""").andExpect(status().isOk())
        calls.clear()

        val payload =
            """{"entry":[{"changes":[{"value":{"metadata":{"phone_number_id":"$phoneNumberId"},""" +
                """"messages":[{"from":"224611223344","type":"text","text":{"body":"bonjour"}}]}}]}]}"""
        mockMvc
            .perform(
                post("/api/v1/whatsapp/webhook")
                    .header("X-Hub-Signature-256", "sha256=" + MetaWebhookSignature.sign(APP_SECRET, payload))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isOk())

        val answer = calls.single { it.path == "/$phoneNumberId/messages" }
        assertEquals("Bearer BIZTOKEN", answer.auth)
        assertTrue("+224611223344" in answer.body, answer.body)
    }

    private fun allowOwnNumber(token: String) {
        TenantContext.set(api.tenantId(token))
        ownNumber.confirm(1)
        TenantContext.clear()
    }

    private fun numberId(): String = Random.nextLong(100_000_000_000, 999_999_999_999).toString()

    data class Call(
        val method: String,
        val path: String,
        val query: String,
        val auth: String?,
        val body: String,
    )

    companion object {
        val calls = CopyOnWriteArrayList<Call>()

        /** Stands in for Meta's Graph API and records what Jikū asked of it. */
        private val meta =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val uri = exchange.requestURI
                    val call =
                        Call(
                            method = exchange.requestMethod,
                            path = uri.rawPath,
                            query = uri.rawQuery.orEmpty(),
                            auth = exchange.requestHeaders.getFirst("Authorization"),
                            body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8),
                        )
                    calls += call
                    val (code, response) =
                        when {
                            call.path == "/oauth/access_token" && "code=bad-code" in call.query ->
                                400 to """{"error":{"message":"Invalid verification code"}}"""
                            call.path == "/oauth/access_token" -> 200 to """{"access_token":"BIZTOKEN"}"""
                            call.path == "/$APP_ID/uploads" -> 200 to """{"id":"upload:abc"}"""
                            call.path.startsWith("/upload:") -> 200 to """{"h":"HANDLE-1"}"""
                            "fields=" in call.query ->
                                200 to """{"display_phone_number":"+224 620 00 00 00","verified_name":"Salle Kaloum"}"""
                            else -> 200 to """{"success":true}"""
                        }
                    val bytes = response.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun metaBaseUrl(registry: DynamicPropertyRegistry) {
            registry.add("jiku.whatsapp.meta.base-url") { "http://127.0.0.1:${meta.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopMeta() {
            meta.stop(0)
        }
    }
}
