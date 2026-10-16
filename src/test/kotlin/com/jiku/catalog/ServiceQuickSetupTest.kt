package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A service created in one step takes bookings at once (JIKU-252): its host
 * gets the default opening hours, it lasts the chosen duration, and a second
 * service reuses the same person instead of doubling them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ServiceQuickSetupTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `a service created in one step offers slots on an opening day`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val serviceId = quickService(api, token, "Coloration", 90)

        val config =
            api
                .get(token, "/api/v1/services/$serviceId/configuration")
                .andReturn()
                .response.contentAsString
        assertEquals(90, JsonPath.read<Int>(config, "$.durationMinutes"))
        api
            .get(token, "/api/v1/services/$serviceId/requirements")
            .andExpect(jsonPath("$[0].type").value("PERSON"))
            .andExpect(jsonPath("$[0].quantity").value(1))

        val link =
            JsonPath.read<String>(
                api
                    .get(token, "/api/v1/services/$serviceId/booking-link")
                    .andReturn()
                    .response.contentAsString,
                "$.token",
            )
        val monday = LocalDate.now(ZoneId.of("Africa/Conakry")).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val slots =
            JsonPath.read<List<String>>(
                mockMvc
                    .perform(get("/api/v1/appointments/$link").param("date", monday.toString()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.slots[*].startsAt",
            )
        assertTrue(slots.isNotEmpty()) { "a quick-set-up service must be bookable on a Monday" }
        val sunday = monday.plusDays(6)
        mockMvc
            .perform(get("/api/v1/appointments/$link").param("date", sunday.toString()))
            .andExpect(jsonPath("$.slots").isEmpty())
    }

    @Test
    fun `a second service reuses the organization's person`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        quickService(api, token, "Coupe", 30)
        quickService(api, token, "Barbe", 15)

        val people =
            JsonPath.read<List<String>>(
                api
                    .get(token, "/api/v1/resources")
                    .andReturn()
                    .response.contentAsString,
                "$[?(@.type == 'PERSON')].id",
            )
        assertEquals(1, people.size)
    }

    @Test
    fun `a service created without the quick setup still starts empty`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val serviceId =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/services", """{"name":"Atelier","timezone":"Africa/Conakry"}""")
                    .andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.get(token, "/api/v1/services/$serviceId/requirements").andExpect(jsonPath("$").isEmpty())
    }

    private fun quickService(
        api: OrganizerApi,
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
}
