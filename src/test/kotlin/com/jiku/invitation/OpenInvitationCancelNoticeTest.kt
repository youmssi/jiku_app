package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * JIKU-187: the people who answered an open invitation "yes" or "maybe" are
 * told by WhatsApp when the event is cancelled. On the free tier the organizer
 * chooses it; the page shows the last moment to answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class OpenInvitationCancelNoticeTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `on the free tier, the people who said yes or maybe are told when the organizer chose it`(output: CapturedOutput) {
        val open = openInvitation("""{"notifyOnCancel":true,"closesAt":"${TestDates.EVENT_YEAR}-11-30T18:00:00Z"}""")
        api
            .get(open.token, "/api/v1/events/${open.eventId}/open-invitation")
            .andExpect(jsonPath("$.notifyOnCancel").value(true))
            .andExpect(jsonPath("$.cancelNoticeIncluded").value(false))
        mockMvc
            .perform(get("/api/v1/open/${open.code}"))
            .andExpect(jsonPath("$.closesAt").value("${TestDates.EVENT_YEAR}-11-30T18:00:00Z"))

        val yes = randomPhone()
        val maybe = randomPhone()
        val no = randomPhone()
        val removed = randomPhone()
        respond(open.code, yes, "YES").andExpect(status().isOk())
        respond(open.code, maybe, "MAYBE").andExpect(status().isOk())
        respond(open.code, no, "NO").andExpect(status().isOk())
        respond(open.code, removed, "YES").andExpect(status().isOk())
        val removedId =
            JsonPath
                .read<List<String>>(
                    api
                        .get(open.token, "/api/v1/events/${open.eventId}/open-invitation/responses")
                        .andReturn()
                        .response.contentAsString,
                    "$[?(@.phone=='+$removed')].id",
                ).single()
        mockMvc
            .perform(
                delete(
                    "/api/v1/events/${open.eventId}/open-invitation/responses/$removedId",
                ).header("Authorization", "Bearer ${open.token}"),
            ).andExpect(status().isNoContent())

        api.post(open.token, "/api/v1/events/${open.eventId}/cancel", "{}").andExpect(status().isOk())

        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assert(told(output, yes) == 1) { "the yes was not told" }
            assert(told(output, maybe) == 1) { "the maybe was not told" }
        }
        assert(told(output, no) == 0) { "a no was told" }
        assert(told(output, removed) == 0) { "a removed person was told" }
    }

    @Test
    fun `on the free tier, nobody is told by default, nor when the organizer cancels silently`(output: CapturedOutput) {
        val silent = openInvitation("{}")
        val phone = randomPhone()
        respond(silent.code, phone, "YES").andExpect(status().isOk())
        api.post(silent.token, "/api/v1/events/${silent.eventId}/cancel", "{}").andExpect(status().isOk())

        val chosen = openInvitation("""{"notifyOnCancel":true}""")
        val other = randomPhone()
        respond(chosen.code, other, "YES").andExpect(status().isOk())
        api.post(chosen.token, "/api/v1/events/${chosen.eventId}/cancel", """{"notifyGuests":false}""").andExpect(status().isOk())

        await().during(Duration.ofSeconds(2)).atMost(4, TimeUnit.SECONDS).untilAsserted {
            assert(told(output, phone) == 0 && told(output, other) == 0) { "someone was told" }
        }
    }

    private data class Open(
        val token: String,
        val eventId: String,
        val code: String,
    )

    private fun openInvitation(settings: String): Open {
        val token = api.register()
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """{"name":"Birthday","timezone":"Africa/Conakry","startDateTime":"${TestDates.EVENT_YEAR}-12-01T18:00:00Z",""" +
                            """"invitationChannels":["EMAIL"],"maxCapacity":20}""",
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        val code =
            JsonPath.read<String>(
                api
                    .put(token, "/api/v1/events/$eventId/open-invitation", settings)
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )
        return Open(token, eventId, code)
    }

    private fun respond(
        code: String,
        phone: String,
        answer: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/open/$code/responses")
                .with {
                    it.remoteAddr = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
                    it
                }.contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Awa Diallo","phone":"+$phone","answer":"$answer"}"""),
        )

    private fun told(
        output: CapturedOutput,
        phone: String,
    ): Int = Regex(Regex.escape("to=+$phone ")).findAll(output.out).count()

    private fun randomPhone(): String = "22463" + Random.nextInt(1_000_000, 9_999_999)
}
