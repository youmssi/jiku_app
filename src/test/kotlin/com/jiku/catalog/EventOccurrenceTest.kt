package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.TestDates.MONDAY
import com.jiku.support.TestDates.TUESDAY
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Occurrences d'un événement multi-dates (ADR 103) : CRUD réservé au brouillon.
 * La facturation unique et la capacité par occurrence arrivent avec le moteur
 * d'inscription (slices suivants).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EventOccurrenceTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `organizer adds and lists occurrences while the event is a draft`() {
        val token = register()
        val eventId = createEvent(token)

        mockMvc
            .perform(
                post("/api/v1/events/$eventId/occurrences")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"startsAt":"${MONDAY}T09:00:00Z","endsAt":"${MONDAY}T12:00:00Z","capacity":80}""",
                    ),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.capacity").value(80))

        mockMvc
            .perform(get("/api/v1/events/$eventId/occurrences").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].capacity").value(80))
    }

    @Test
    fun `occurrence is rejected for a missing or published event`() {
        val token = register()
        mockMvc
            .perform(
                post("/api/v1/events/${UUID.randomUUID()}/occurrences")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"startsAt":"${MONDAY}T09:00:00Z"}"""),
            ).andExpect(status().isNotFound())

        val eventId = createEvent(token)
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/occurrences")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"startsAt":"${TUESDAY}T09:00:00Z"}"""),
            ).andExpect(status().isConflict())
    }

    private fun register(): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Occ Org","email":"occ-${UUID.randomUUID()}@test.example","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )

    private fun createEvent(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Séminaire","timezone":"Africa/Conakry","startDateTime":"${MONDAY}T09:00:00Z","invitationChannels":["EMAIL"]}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )
}
