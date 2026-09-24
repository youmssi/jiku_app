package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.shared.JwtService
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * JIKU-116: an operator works through one link on the events and services the
 * organizer put in their scope, and only takes the actions the organizer allowed.
 * Anything outside that scope, another organization's included, answers like an
 * unknown resource; revocation takes effect on the next request; the links
 * handed out before operators existed keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OperatorTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var invitationTokens: InvitationTokenService

    @Autowired
    lateinit var jwt: JwtService

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `an operator works across their events and services with only the actions allowed`() {
        val organizer = api.register()
        val eventId = publishedEvent(organizer)
        val otherEventId = publishedEvent(organizer)
        val serviceId = service(organizer)
        val ticketCode = confirmedTicket(organizer, eventId, "Mariama")

        val created =
            api
                .post(
                    organizer,
                    "/api/v1/operators",
                    """{"label":"Awa","eventIds":["$eventId"],"serviceIds":["$serviceId"],"actions":["CHECK_IN","QUEUE"]}""",
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.billable").value(true))
                .andExpect(jsonPath("$.events[0].id").value(eventId))
                .andExpect(jsonPath("$.services[0].name").value("Accueil"))
                .andReturn()
                .response.contentAsString
        val operatorId = JsonPath.read<String>(created, "$.id")
        val code = JsonPath.read<String>(created, "$.code")
        api
            .get(organizer, "/api/v1/operators")
            .andExpect(jsonPath("$.billableSeats").value(1))
            .andExpect(jsonPath("$.operators[0].link").value("http://localhost:3000/operator/$code"))

        // The code opens the console on the operator's events and services.
        val link = resolve(code)
        mockMvc
            .perform(get("/api/v1/operator/$link"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("Awa"))
            .andExpect(jsonPath("$.events.length()").value(1))
            .andExpect(jsonPath("$.events[0].id").value(eventId))
            .andExpect(jsonPath("$.services[0].id").value(serviceId))

        // Check-in at the event is attributed to the operator.
        operatorPost("/api/v1/operator/$link/events/$eventId/scan", """{"ticketCode":"$ticketCode"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))
        api
            .get(organizer, "/api/v1/events/$eventId/dashboard")
            .andExpect(jsonPath("$.entrances[?(@.label=='Awa')].checkedIn").value(1))

        // The service's line is served too.
        mockMvc
            .perform(get("/api/v1/operator/$link/services/$serviceId"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serviceName").value("Accueil"))

        // Recording a payment was not allowed.
        operatorPost("/api/v1/operator/$link/events/$eventId/tickets/$ticketCode/paid", """{"method":"CASH"}""")
            .andExpect(status().isForbidden())

        // Another event of the same organization is out of scope.
        operatorPost("/api/v1/operator/$link/events/$otherEventId/scan", """{"ticketCode":"$ticketCode"}""")
            .andExpect(status().isNotFound())

        // The organizer narrows the scope: the event is gone at once, and the seat with the service.
        api
            .put(
                organizer,
                "/api/v1/operators/$operatorId",
                """{"label":"Awa","eventIds":["$otherEventId"],"actions":["CHECK_IN"]}""",
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.billable").value(false))
        mockMvc.perform(get("/api/v1/operator/$link/events/$eventId")).andExpect(status().isNotFound())
        mockMvc.perform(get("/api/v1/operator/$link/services/$serviceId")).andExpect(status().isNotFound())

        // Revocation takes effect on the next request, and the code stops resolving.
        api.post(organizer, "/api/v1/operators/$operatorId/revoke", "{}").andExpect(status().isOk())
        mockMvc.perform(get("/api/v1/operator/$link")).andExpect(status().isForbidden())
        mockMvc.perform(get("/api/v1/operator-codes/$code")).andExpect(status().isNotFound())
    }

    @Test
    fun `an operator link never reaches another organization`() {
        val organizer = api.register()
        val eventId = publishedEvent(organizer)
        val code = operatorCode(organizer, """{"label":"Door","eventIds":["$eventId"],"actions":["CHECK_IN"]}""")
        val link = resolve(code)

        val stranger = api.register()
        val foreignEventId = publishedEvent(stranger)
        val foreignTicket = confirmedTicket(stranger, foreignEventId, "Fanta")

        operatorPost("/api/v1/operator/$link/events/$foreignEventId/scan", """{"ticketCode":"$foreignTicket"}""")
            .andExpect(status().isNotFound())
        operatorPost("/api/v1/operator/$link/events/$eventId/scan", """{"ticketCode":"$foreignTicket"}""")
            .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))
    }

    @Test
    fun `an operator needs a scope, published events and existing services`() {
        val organizer = api.register()
        val draftEventId = api.createEvent(organizer)

        api
            .post(organizer, "/api/v1/operators", """{"label":"Nobody","actions":["CHECK_IN"]}""")
            .andExpect(status().isBadRequest())
        api
            .post(organizer, "/api/v1/operators", """{"label":"Door","eventIds":["$draftEventId"],"actions":["CHECK_IN"]}""")
            .andExpect(status().isConflict())
        api
            .post(organizer, "/api/v1/operators", """{"label":"Desk","serviceIds":["${UUID.randomUUID()}"],"actions":["QUEUE"]}""")
            .andExpect(status().isNotFound())
        api
            .post(organizer, "/api/v1/operators", """{"label":"Idle","serviceIds":["${service(organizer)}"],"actions":[]}""")
            .andExpect(status().isBadRequest())
    }

    @Test
    fun `a door link handed out before operators existed still opens its event`() {
        val organizer = api.register()
        val eventId = publishedEvent(organizer)
        val ticketCode = confirmedTicket(organizer, eventId, "Kadiatou")
        val operatorId =
            JsonPath.read<String>(
                api
                    .post(organizer, "/api/v1/events/$eventId/validators", """{"label":"Gate B"}""")
                    .andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        // A token as the door links of JIKU-23 were signed.
        val legacy =
            jwt.sign(
                operatorId,
                mapOf("eventId" to eventId, "tenantId" to api.tenantId(organizer), "type" to "validator"),
            )

        operatorPost("/api/v1/checkin/$legacy/scan", """{"ticketCode":"$ticketCode"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))
        mockMvc
            .perform(get("/api/v1/checkin/$legacy"))
            .andExpect(jsonPath("$.validatorLabel").value("Gate B"))
            .andExpect(jsonPath("$.actions.length()").value(2))
    }

    private fun publishedEvent(organizer: String): String = api.createEvent(organizer).also { api.publish(organizer, it) }

    private fun service(organizer: String): String =
        JsonPath.read(
            api
                .post(organizer, "/api/v1/services", """{"name":"Accueil","timezone":"Africa/Conakry"}""")
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    private fun confirmedTicket(
        organizer: String,
        eventId: String,
        firstName: String,
    ): String {
        val guestId = api.importGuest(organizer, eventId, firstName)
        val rsvp = invitationTokens.issue(UUID.fromString(guestId), UUID.fromString(eventId), api.tenantId(organizer))
        return JsonPath.read(
            mockMvc
                .perform(post("/api/v1/rsvp/$rsvp/confirm"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.ticketCode",
        )
    }

    private fun operatorCode(
        organizer: String,
        json: String,
    ): String =
        JsonPath.read(
            api
                .post(organizer, "/api/v1/operators", json)
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.code",
        )

    private fun resolve(code: String): String =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/operator-codes/$code"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.token",
        )

    private fun operatorPost(
        path: String,
        json: String,
    ): ResultActions = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
}
