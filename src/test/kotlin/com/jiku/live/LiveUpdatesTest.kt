package com.jiku.live

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * JIKU-214: a screen follows its topic with a ticket from its own endpoint, and
 * hears "change" once something it shows was written, whatever wrote it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class LiveUpdatesTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @LocalServerPort
    var port: Int = 0

    private val api by lazy { OrganizerApi(mockMvc) }
    private val http = HttpClient.newHttpClient()

    @Test
    fun `the dashboard hears a guest added to its event`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        val ticket = liveTicket(token, eventId)

        val stream = open(ticket)
        assertThat(stream.status).isEqualTo(200)
        assertThat(stream.nextEvent()).isEqualTo("ready" to "event:$eventId")

        api.importGuest(token, eventId, "Mariama")

        assertThat(stream.nextEvent()).isEqualTo("change" to "event:$eventId")
        stream.close()
    }

    @Test
    fun `a client following their place hears the counter call the next person`() {
        val token = api.register()
        val serviceId =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/services", """{"name":"Accueil","timezone":"Africa/Conakry"}""")
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        val code =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/services/$serviceId/booking-link")
                    .andReturn()
                    .response.contentAsString,
                "$.shortCode",
            )
        val ticketCode =
            JsonPath.read<String>(
                mockMvc
                    .perform(
                        post("/api/v1/r/$code/line")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"clientName":"Aminata","clientPhone":"+224620000090"}"""),
                    ).andReturn()
                    .response.contentAsString,
                "$.ticketCode",
            )
        val ticket =
            JsonPath.read<String>(
                mockMvc
                    .perform(post("/api/v1/r/$code/line/$ticketCode/live"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.ticket",
            )

        val stream = open(ticket)
        assertThat(stream.nextEvent()).isEqualTo("ready" to "service:$serviceId")

        api.post(token, "/api/v1/services/$serviceId/day-line/next?counter=Guichet 2", "{}").andExpect(status().isOk())

        assertThat(stream.nextEvent()).isEqualTo("change" to "service:$serviceId")
        stream.close()
    }

    @Test
    fun `a ticket not in today's line gets no live ticket`() {
        val token = api.register()
        val serviceId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/services",
                        """{"name":"Accueil","timezone":"Africa/Conakry"}""",
                    ).andReturn()
                    .response.contentAsString,
                "$.id",
            )
        val code =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/services/$serviceId/booking-link")
                    .andReturn()
                    .response.contentAsString,
                "$.shortCode",
            )

        mockMvc.perform(post("/api/v1/r/$code/line/unknown/live")).andExpect(status().isNotFound())
    }

    @Test
    fun `another organization cannot follow an event that is not its own`() {
        val owner = api.register()
        val eventId = api.createEvent(owner)

        api.post(api.register(), "/api/v1/events/$eventId/dashboard/live", "{}").andExpect(status().isNotFound())
    }

    @Test
    fun `a stream needs a live ticket, not a session token or a forged one`() {
        val token = api.register()

        assertThat(open(token).status).isEqualTo(401)
        assertThat(open("not-a-ticket").status).isEqualTo(401)
    }

    private fun liveTicket(
        token: String,
        eventId: String,
    ): String =
        JsonPath.read(
            api
                .post(token, "/api/v1/events/$eventId/dashboard/live", "{}")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.ticket",
        )

    private fun open(ticket: String): Stream {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/api/v1/live/stream?ticket=${URLEncoder.encode(ticket, Charsets.UTF_8)}"))
                .header("Accept", "text/event-stream")
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        return Stream(response.statusCode(), BufferedReader(InputStreamReader(response.body())))
    }

    private class Stream(
        val status: Int,
        private val reader: BufferedReader,
    ) {
        /** The next named event and its data, skipping heartbeats; fails after 10 s. */
        fun nextEvent(): Pair<String, String> =
            CompletableFuture
                .supplyAsync {
                    var name: String? = null
                    var data: String? = null
                    while (true) {
                        val line = reader.readLine() ?: error("stream closed")
                        when {
                            line.startsWith("event:") -> name = line.removePrefix("event:")
                            line.startsWith("data:") -> data = line.removePrefix("data:")
                            line.isEmpty() && name != null -> return@supplyAsync name to data.orEmpty()
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }.get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)

        fun close() = reader.close()
    }
}
