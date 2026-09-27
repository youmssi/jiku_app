package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JIKU-184, ADR 106: an event shared in groups without a guest list. People
 * answer yes, maybe or no with their number; a yes takes its places (the person
 * and their companions) under the event's capacity and gets a QR ticket, and
 * those people count against the guest tier.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OpenInvitationFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a person answers yes with companions, gets a ticket, and the organizer sees the count`() {
        val open = openInvitation(capacity = 10)
        page(open.code)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.eventName").value("Birthday"))
            .andExpect(jsonPath("$.welcomeMessage").value("Come celebrate!"))
            .andExpect(jsonPath("$.accepting").value(true))
            .andExpect(jsonPath("$.maxCompanions").value(3))

        val answered =
            respond(open.code, "Awa Diallo", "+224 620 11 22 33", "YES", companions = 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("YES"))
                .andExpect(jsonPath("$.companions").value(2))
                .andReturn()
                .response.contentAsString
        val ticketToken = JsonPath.read<String>(answered, "$.ticketToken")
        mockMvc
            .perform(get("/api/v1/rsvp/$ticketToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.guestName").value("Awa Diallo"))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.companions").value(2))

        respond(open.code, "Moussa", "+224 621 00 00 01", "MAYBE").andExpect(status().isOk())
        respond(open.code, "Fanta", "+224 621 00 00 02", "NO").andExpect(status().isOk())

        settings(open)
            .andExpect(jsonPath("$.counts.yes").value(1))
            .andExpect(jsonPath("$.counts.maybe").value(1))
            .andExpect(jsonPath("$.counts.no").value(1))
            .andExpect(jsonPath("$.counts.expected").value(3))
            .andExpect(jsonPath("$.remainingPlaces").value(7))
        api
            .get(open.token, "/api/v1/events/${open.eventId}/open-invitation/responses")
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[?(@.name=='Awa Diallo')].phone").value("+224620112233"))
            .andExpect(jsonPath("$[?(@.name=='Awa Diallo')].channel").value("WEB"))
    }

    @Test
    fun `one answer per number, changed in place, giving back or taking the difference`() {
        val open = openInvitation(capacity = 10)
        respond(open.code, "Awa", "+224 620 11 22 33", "YES", companions = 3).andExpect(status().isOk())
        settings(open).andExpect(jsonPath("$.remainingPlaces").value(6))

        val fewer =
            respond(open.code, "Awa Diallo", "00224620112233", "YES", companions = 1)
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        settings(open)
            .andExpect(jsonPath("$.counts.yes").value(1))
            .andExpect(jsonPath("$.counts.expected").value(2))
            .andExpect(jsonPath("$.remainingPlaces").value(8))

        respond(
            open.code,
            "Awa Diallo",
            "224620112233",
            "NO",
        ).andExpect(status().isOk()).andExpect(jsonPath("$.ticketToken").doesNotExist())
        settings(open)
            .andExpect(jsonPath("$.counts.yes").value(0))
            .andExpect(jsonPath("$.counts.no").value(1))
            .andExpect(jsonPath("$.remainingPlaces").value(10))
        mockMvc
            .perform(get("/api/v1/rsvp/${JsonPath.read<String>(fewer, "$.ticketToken")}"))
            .andExpect(jsonPath("$.status").value("DECLINED"))

        respond(open.code, "Awa Diallo", "+224620112233", "YES").andExpect(status().isOk()).andExpect(jsonPath("$.ticketToken").exists())
        settings(open).andExpect(jsonPath("$.remainingPlaces").value(9))
    }

    @Test
    fun `a yes needs its places, a maybe does not`() {
        val open = openInvitation(capacity = 3)
        respond(open.code, "Awa", "+224 620 00 00 01", "YES", companions = 2).andExpect(status().isOk())
        respond(open.code, "Moussa", "+224 620 00 00 02", "YES").andExpect(status().isConflict())
        respond(open.code, "Moussa", "+224 620 00 00 02", "MAYBE").andExpect(status().isOk())
        page(open.code).andExpect(jsonPath("$.accepting").value(false)).andExpect(jsonPath("$.closedReason").value("FULL"))
    }

    @Test
    fun `yes answers and their companions count against the guest tier`() {
        val open = openInvitation(capacity = null, maxCompanions = 10)
        repeat(9) { index ->
            respond(open.code, "Guest $index", "+224 620 00 10 ${10 + index}", "YES", companions = 10).andExpect(status().isOk())
        }
        respond(open.code, "One too many", "+224 620 00 20 00", "YES", companions = 10).andExpect(status().isPaymentRequired())
        respond(open.code, "Still room", "+224 620 00 20 01", "YES").andExpect(status().isOk())
        respond(open.code, "Maybe later", "+224 620 00 20 02", "MAYBE").andExpect(status().isOk())
        settings(open).andExpect(jsonPath("$.counts.expected").value(100))
    }

    @Test
    fun `the organizer removes a person, whose places come back and whose number cannot answer again`() {
        val open = openInvitation(capacity = 5)
        respond(open.code, "Intruder", "+224 620 99 99 99", "YES", companions = 2).andExpect(status().isOk())
        val id =
            JsonPath
                .read<List<String>>(
                    api
                        .get(open.token, "/api/v1/events/${open.eventId}/open-invitation/responses")
                        .andReturn()
                        .response.contentAsString,
                    "$[*].id",
                ).single()
        mockMvc
            .perform(delete("/api/v1/events/${open.eventId}/open-invitation/responses/$id").header("Authorization", "Bearer ${open.token}"))
            .andExpect(status().isNoContent())
        settings(open).andExpect(jsonPath("$.remainingPlaces").value(5)).andExpect(jsonPath("$.counts.yes").value(0))
        respond(open.code, "Intruder", "+224 620 99 99 99", "YES").andExpect(status().isForbidden())
    }

    @Test
    fun `a closed invitation takes no answer, an unknown code or a draft event shows nothing`() {
        val open = openInvitation(capacity = 5)
        api
            .put(open.token, "/api/v1/events/${open.eventId}/open-invitation", """{"enabled":false}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accepting").value(false))
        page(open.code).andExpect(jsonPath("$.closedReason").value("DISABLED"))
        respond(open.code, "Awa", "+224 620 11 22 33", "YES").andExpect(status().isConflict())
        page("NOPE2345").andExpect(status().isNotFound())

        val token = api.register()
        val draft = api.createEvent(token)
        val code =
            JsonPath.read<String>(
                api
                    .put(token, "/api/v1/events/$draft/open-invitation", "{}")
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )
        page(code).andExpect(status().isNotFound())
    }

    @Test
    fun `another organization cannot see or change an open invitation`() {
        val open = openInvitation(capacity = 5)
        respond(open.code, "Awa", "+224 620 11 22 33", "YES").andExpect(status().isOk())
        val other = api.register()
        api.get(other, "/api/v1/events/${open.eventId}/open-invitation").andExpect(status().isNotFound())
        api.get(other, "/api/v1/events/${open.eventId}/open-invitation/responses").andExpect(status().isNotFound())
        api.put(other, "/api/v1/events/${open.eventId}/open-invitation", "{}").andExpect(status().isNotFound())
    }

    @Test
    fun `people racing for the last place never both get it`() {
        val open = openInvitation(capacity = 1)
        val people = 8
        val ready = CountDownLatch(people)
        val go = CountDownLatch(1)
        val accepted = AtomicInteger()
        val refused = AtomicInteger()
        val pool = Executors.newFixedThreadPool(people)
        repeat(people) { index ->
            pool.submit {
                ready.countDown()
                go.await()
                when (respond(open.code, "Racer $index", "+224 620 30 00 ${10 + index}", "YES").andReturn().response.status) {
                    200 -> accepted.incrementAndGet()
                    409 -> refused.incrementAndGet()
                }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(1, accepted.get())
        assertEquals(people - 1, refused.get())
        settings(open).andExpect(jsonPath("$.counts.yes").value(1)).andExpect(jsonPath("$.remainingPlaces").value(0))
    }

    private data class Open(
        val token: String,
        val eventId: String,
        val code: String,
    )

    private fun openInvitation(
        capacity: Int?,
        maxCompanions: Int = 3,
    ): Open {
        val token = api.register()
        val capacityField = capacity?.let { ""","maxCapacity":$it""" } ?: ""
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """{"name":"Birthday","timezone":"Africa/Conakry","startDateTime":"${TestDates.EVENT_YEAR}-12-01T18:00:00Z",""" +
                            """"invitationChannels":["EMAIL"]$capacityField}""",
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        api.get(token, "/api/v1/events/$eventId/open-invitation").andExpect(status().isNoContent())
        val code =
            JsonPath.read<String>(
                api
                    .put(
                        token,
                        "/api/v1/events/$eventId/open-invitation",
                        """{"welcomeMessage":"Come celebrate!","maxCompanions":$maxCompanions}""",
                    ).andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )
        return Open(token, eventId, code)
    }

    private fun settings(open: Open): ResultActions =
        api.get(open.token, "/api/v1/events/${open.eventId}/open-invitation").andExpect(status().isOk())

    private fun page(code: String): ResultActions =
        mockMvc.perform(
            get("/api/v1/open/$code").with {
                it.remoteAddr = randomIp()
                it
            },
        )

    private fun respond(
        code: String,
        name: String,
        phone: String,
        answer: String,
        companions: Int = 0,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/open/$code/responses")
                .with {
                    it.remoteAddr = randomIp()
                    it
                }.contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","phone":"$phone","answer":"$answer","companions":$companions}"""),
        )

    private fun randomIp(): String = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
}
