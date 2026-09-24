package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
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

/**
 * JIKU-113: a client takes a ticket from the QR at the entrance, follows how many
 * people are ahead, and learns which counter to go to once called — without ever
 * seeing who else is waiting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ClientQueueTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `clients take tickets, follow their place and learn their counter`() {
        val token = api.register()
        val serviceId = createService(token)
        val code = shortCode(token, serviceId)

        val first = JsonPath.read<String>(take(code, "Aminata", "+224620000010").andReturn().response.contentAsString, "$.ticketCode")
        take(code, "Ibrahima", "+224620000011")
            .andExpect(jsonPath("$.status").value("WAITING"))
            .andExpect(jsonPath("$.peopleAhead").value(1))
            .andExpect(jsonPath("$.estimatedWaitMinutes").value(30))
            .andExpect(jsonPath("$.clientName").doesNotExist())
        val second = latestTicket(token, serviceId)

        api
            .post(token, "/api/v1/services/$serviceId/day-line/next?counter=Guichet 4", "{}")
            .andExpect(status().isOk())

        place(code, first)
            .andExpect(jsonPath("$.status").value("CALLED"))
            .andExpect(jsonPath("$.counter").value("Guichet 4"))
        place(code, second).andExpect(jsonPath("$.peopleAhead").value(0))
    }

    @Test
    fun `a ticket that is not in today's line is not found`() {
        val token = api.register()
        val code = shortCode(token, createService(token))

        place(code, "unknown-ticket").andExpect(status().isNotFound())
    }

    @Test
    fun `a service that takes no walk-ins issues no ticket`() {
        val token = api.register()
        val serviceId = createService(token)
        api.put(token, "/api/v1/services/$serviceId/configuration", """{"walkInsAllowed":false}""").andExpect(status().isOk())

        take(shortCode(token, serviceId), "Aminata", "+224620000012").andExpect(status().isConflict())
    }

    private fun take(
        code: String,
        name: String,
        phone: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/r/$code/line")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clientName":"$name","clientPhone":"$phone"}"""),
        )

    private fun place(
        code: String,
        ticketCode: String,
    ): ResultActions = mockMvc.perform(get("/api/v1/r/$code/line/$ticketCode"))

    private fun createService(token: String): String =
        JsonPath.read(
            api
                .post(token, "/api/v1/services", """{"name":"Accueil","timezone":"Africa/Conakry"}""")
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    private fun shortCode(
        token: String,
        serviceId: String,
    ): String =
        JsonPath.read(
            api
                .get(token, "/api/v1/services/$serviceId/booking-link")
                .andReturn()
                .response.contentAsString,
            "$.shortCode",
        )

    private fun latestTicket(
        token: String,
        serviceId: String,
    ): String =
        JsonPath
            .read<List<String>>(
                api
                    .get(token, "/api/v1/services/$serviceId/day-line")
                    .andReturn()
                    .response.contentAsString,
                "$.entries[?(@.clientPhone=='+224620000011')].ticketCode",
            ).single()
}
