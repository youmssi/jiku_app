package com.jiku.messaging

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import kotlin.test.assertTrue

/**
 * JIKU-114: a client called in the line is told it is their turn, and at which
 * counter, through the service's client channel — and not at all when the
 * service sends clients nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, ReminderChannelTest.ProvidersConfig::class)
@TestPropertySource(properties = ["jiku.whatsapp.transport=test", "jiku.sms.transport=test"])
class ClientCalledNoticeTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var providers: ReminderChannelTest.Providers

    private val api by lazy { OrganizerApi(mockMvc) }

    @BeforeEach
    fun reset() {
        providers.whatsApps.clear()
        providers.smses.clear()
    }

    @Test
    fun `the called client hears which counter to go to`() {
        val token = api.register()
        val serviceId = createService(token)
        api
            .put(token, "/api/v1/services/$serviceId/configuration", """{"reminderChannel":"SMS"}""")
            .andExpect(status().isOk())
        walkIn(token, serviceId, "+224620000020")

        api.post(token, "/api/v1/services/$serviceId/day-line/next?counter=Guichet 4", "{}").andExpect(status().isOk())

        await().atMost(Duration.ofSeconds(10)).until { providers.smses.any { it.to == "+224620000020" } }
        assertTrue(
            providers.smses
                .single { it.to == "+224620000020" }
                .body
                .contains("Guichet 4"),
        )
    }

    @Test
    fun `a service that messages no one sends nothing on a call`() {
        val token = api.register()
        val serviceId = createService(token)
        walkIn(token, serviceId, "+224620000021")

        api.post(token, "/api/v1/services/$serviceId/day-line/next", "{}").andExpect(status().isOk())

        Thread.sleep(1_000)
        assertTrue(providers.smses.none { it.to == "+224620000021" })
        assertTrue(providers.whatsApps.none { it.to == "+224620000021" })
    }

    private fun createService(token: String): String =
        JsonPath.read(
            api
                .post(token, "/api/v1/services", """{"name":"Guichets","timezone":"Africa/Conakry"}""")
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    private fun walkIn(
        token: String,
        serviceId: String,
        phone: String,
    ) {
        api
            .post(token, "/api/v1/services/$serviceId/day-line/walk-in", """{"clientName":"Fanta","clientPhone":"$phone"}""")
            .andExpect(status().isCreated())
    }
}
