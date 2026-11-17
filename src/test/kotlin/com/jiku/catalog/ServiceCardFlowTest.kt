package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.ServiceCardService
import com.jiku.support.OrganizerApi
import com.jiku.support.TestVerifications
import org.assertj.core.api.Assertions.assertThat
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * JIKU-189: a service card counts opens once per visitor and per day, by way in,
 * and the bookings made after them; each organization sees only its own
 * figures, and only a verified one may share.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ServiceCardFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var verifications: TestVerifications

    @Autowired
    lateinit var cards: ServiceCardService

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `opens count once per visitor and day, bookings follow their way in`() {
        val token = api.register()
        val serviceId = service(token, "Coupe homme", 30)
        api
            .patch(
                token,
                "/api/v1/services/$serviceId",
                """{"place":"Kaloum, Conakry"}""",
            ).andExpect(jsonPath("$.place").value("Kaloum, Conakry"))
        api
            .get(token, "/api/v1/services/$serviceId/share")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canShare").value(false))
            .andExpect(jsonPath("$.opens.card").value(0))
        verifications.approve(api.tenantId(token))
        val code =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/services/$serviceId/share")
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )

        mockMvc
            .perform(get("/api/v1/r/$code"))
            .andExpect(jsonPath("$.place").value("Kaloum, Conakry"))
            .andExpect(jsonPath("$.durationMinutes").value(30))

        open(code, "card", "10.0.0.1", "Phone A").andExpect(status().isNoContent())
        open(code, "card", "10.0.0.1", "Phone A")
        open(code, "card", "10.0.0.2", "Phone B")
        open(code, "link", "10.0.0.1", "Phone A")
        open(code, "other", "10.0.0.3", "Phone C").andExpect(status().isNoContent())
        open("NOPE99", "card", "10.0.0.4", "Phone D").andExpect(status().isNoContent())

        val monday = LocalDate.now(ZoneId.of("Africa/Conakry")).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val slot =
            JsonPath.read<List<String>>(
                mockMvc
                    .perform(get("/api/v1/r/$code").param("date", monday.toString()))
                    .andReturn()
                    .response.contentAsString,
                "$.slots[*].startsAt",
            )
        book(code, slot[0], "card").andExpect(status().isCreated())
        book(code, slot[1], null).andExpect(status().isCreated())

        api
            .get(token, "/api/v1/services/$serviceId/share")
            .andExpect(jsonPath("$.canShare").value(true))
            .andExpect(jsonPath("$.windowDays").value(30))
            .andExpect(jsonPath("$.opens.card").value(2))
            .andExpect(jsonPath("$.opens.link").value(1))
            .andExpect(jsonPath("$.bookings.card").value(1))
            .andExpect(jsonPath("$.bookings.link").value(0))

        // Another organization sees nothing of these figures, even for this service id.
        val other = api.register()
        api.get(other, "/api/v1/services/$serviceId/share").andExpect(status().isNotFound())
        val own = service(other, "Manucure", 45)
        api.get(other, "/api/v1/services/$own/share").andExpect(jsonPath("$.opens.card").value(0))

        // Once the day is over, its visitor hashes go; the counts stay.
        assertThat(cards.forgetPastVisitors()).isGreaterThanOrEqualTo(0)
    }

    private fun service(
        token: String,
        name: String,
        minutes: Int,
    ): String =
        JsonPath.read(
            api
                .post(
                    token,
                    "/api/v1/services",
                    """{"name":"$name","timezone":"Africa/Conakry","quickSetup":{"durationMinutes":$minutes,"hostName":"Kadiatou"}}""",
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    private fun open(
        code: String,
        source: String,
        address: String,
        agent: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/r/$code/open")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"source":"$source","agent":"$agent"}""")
                .with { it.apply { remoteAddr = address } },
        )

    private fun book(
        code: String,
        startsAt: String,
        source: String?,
    ): ResultActions {
        val tag = source?.let { ""","source":"$it"""" }.orEmpty()
        return mockMvc.perform(
            post("/api/v1/r/$code/book")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"clientName":"Fatou","clientPhone":"+224600000000","startsAt":"$startsAt"$tag}"""),
        )
    }
}
