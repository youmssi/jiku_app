package com.jiku.checkin

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates.EVENT_YEAR
import com.jiku.ticket.TicketingModuleApi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * Regression (JIKU-110): the QR of an appointment belongs to no event. Scanned at
 * an event's entrance it used to fail the request; it must simply not be found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ServiceTicketAtEventGateTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var ticketing: TicketingModuleApi

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `an appointment ticket scanned at an event entrance is not found`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        api.post(token, "/api/v1/events/$eventId/publish", "{}").andExpect(status().isOk())
        val tenantId =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/auth/me")
                    .andReturn()
                    .response.contentAsString,
                "$.tenantId",
            )
        val appointmentCode =
            TenantContext.withTenant(tenantId) {
                ticketing.issueAppointment(
                    guestId = UUID.randomUUID(),
                    startsAt = Instant.parse("${EVENT_YEAR}-12-01T09:00:00Z"),
                    endsAt = Instant.parse("${EVENT_YEAR}-12-01T09:30:00Z"),
                    serviceId = UUID.randomUUID(),
                    professionalName = null,
                    clientName = "Awa",
                    clientPhone = "+224620000000",
                )
            }

        api
            .post(token, "/api/v1/events/$eventId/checkin/scan", """{"ticketCode":"$appointmentCode"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("NOT_FOUND"))

        // The offline sync of a validator device takes the same path.
        val link =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/events/$eventId/validators", """{"label":"Gate"}""")
                    .andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.link",
            )
        mockMvc
            .perform(
                post("/api/v1/checkin/${link.substringAfter("/checkin/")}/sync")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"items":[{"ticketCode":"$appointmentCode","scannedAt":"${EVENT_YEAR}-12-01T09:05:00Z"}]}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].outcome").value("NOT_FOUND"))
    }
}
