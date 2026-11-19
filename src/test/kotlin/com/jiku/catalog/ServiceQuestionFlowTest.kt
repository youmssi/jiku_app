package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.ServiceClientRetentionJob
import com.jiku.support.OrganizerApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * JIKU-191: a service asks at most three choice questions before the times,
 * within WhatsApp's limits; an answer can send the client to another service;
 * the answers are kept with the booking, shown to staff and exported, and go
 * with the booking and with the client's details.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ServiceQuestionFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var retention: ServiceClientRetentionJob

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `questions fit WhatsApp's limits and an answer can lead to another service`() {
        val token = api.register()
        val serviceId = service(token, "Soins dentaires")
        val scaling = service(token, "Détartrage")

        fun put(body: String): ResultActions = api.put(token, "/api/v1/services/$serviceId/questions", body)

        val four = (1..4).joinToString(",") { question("Question $it", "Oui", "Non") }
        put("""{"questions":[$four]}""").andExpect(status().isBadRequest())
        put("""{"questions":[${question("Motif ?", "Une réponse beaucoup trop longue", "Non")}]}""").andExpect(status().isBadRequest())
        put("""{"questions":[{"prompt":"Motif ?","choices":[{"label":"Seul"}]}]}""").andExpect(status().isBadRequest())
        put("""{"questions":[${question("Motif ?", "Oui", "oui")}]}""").andExpect(status().isBadRequest())
        val eleven = (1..11).joinToString(",") { """{"label":"Choix $it"}""" }
        put("""{"questions":[{"prompt":"Motif ?","choices":[$eleven]}]}""").andExpect(status().isBadRequest())
        put("""{"questions":[{"prompt":"Motif ?","choices":[{"label":"Ici","routeServiceId":"$serviceId"},{"label":"Non"}]}]}""")
            .andExpect(status().isBadRequest())

        // Another organization's service is not a destination.
        val other = api.register()
        val foreign = service(other, "Ailleurs")
        put("""{"questions":[{"prompt":"Motif ?","choices":[{"label":"Ailleurs","routeServiceId":"$foreign"},{"label":"Non"}]}]}""")
            .andExpect(status().isBadRequest())

        put(
            """{"questions":[{"prompt":"Quel est votre besoin ?","choices":[{"label":"Douleur"},{"label":"Contrôle"},""" +
                """{"label":"Détartrage","routeServiceId":"$scaling"}]},${question("Première visite ?", "Oui", "Non")}]}""",
        ).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].choices[2].routeServiceId").value(scaling))

        val code = shortCode(token, serviceId)
        mockMvc
            .perform(get("/api/v1/r/$code"))
            .andExpect(jsonPath("$.questions.length()").value(2))
            .andExpect(jsonPath("$.questions[0].prompt").value("Quel est votre besoin ?"))
            .andExpect(jsonPath("$.questions[0].choices[0].routeCode").doesNotExist())
            .andExpect(jsonPath("$.questions[0].choices[2].routeCode").value(shortCode(token, scaling)))
        // The other organization still has no questions on its own service.
        api.get(other, "/api/v1/services/$foreign/questions").andExpect(jsonPath("$.length()").value(0))
        api.get(other, "/api/v1/services/$serviceId/questions").andExpect(status().isNotFound())
    }

    @Test
    fun `answers are kept with the booking, shown to staff, exported, and erased with it`() {
        val token = api.register()
        val serviceId = service(token, "Consultation")
        api
            .put(
                token,
                "/api/v1/services/$serviceId/questions",
                """{"questions":[${question("Quel est votre besoin ?", "Douleur", "Contrôle")}]}""",
            ).andExpect(status().isOk())
        val asked =
            api
                .get(token, "/api/v1/services/$serviceId/questions")
                .andReturn()
                .response.contentAsString
        val questionId = JsonPath.read<String>(asked, "$[0].id")
        val pain = JsonPath.read<String>(asked, "$[0].choices[0].id")
        val code = shortCode(token, serviceId)
        val monday = LocalDate.now(ZoneId.of("Africa/Conakry")).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val slots = slots(code, monday)

        // Every question needs one of its own answers.
        book(code, slots[0], "Awa", "").andExpect(status().isBadRequest())
        book(code, slots[0], "Awa", """{"questionId":"$questionId","choiceId":"$questionId"}""").andExpect(status().isBadRequest())

        val awa = bookingToken(book(code, slots[0], "Awa", """{"questionId":"$questionId","choiceId":"$pain"}"""))
        book(code, slots[1], "Binta", """{"questionId":"$questionId","choiceId":"$pain"}""").andExpect(status().isCreated())

        // The pending requests show what each client answered.
        val requests =
            api
                .get(token, "/api/v1/services/$serviceId/day-line/requests?date=$monday")
                .andExpect(jsonPath("$[0].answers[0].question").value("Quel est votre besoin ?"))
                .andExpect(jsonPath("$[0].answers[0].answer").value("Douleur"))
                .andReturn()
                .response.contentAsString
        val awaRequest = JsonPath.read<String>(requests, "$[0].id")
        val bintaRequest = JsonPath.read<String>(requests, "$[1].id")

        // Once confirmed, the appointment's answers follow it into the day's line.
        api.post(token, "/api/v1/services/$serviceId/day-line/requests/$awaRequest/accept", "{}").andExpect(status().is2xxSuccessful())
        val line =
            api
                .get(token, "/api/v1/services/$serviceId/day-line?date=$monday")
                .andReturn()
                .response.contentAsString
        val ticketId = JsonPath.read<String>(line, "$.entries[0].id")
        assertThat(JsonPath.read<String>(line, "$.answers['$ticketId'][0].answer")).isEqualTo("Douleur")

        // The export lists both bookings with a column per question.
        val csv =
            api
                .get(token, "/api/v1/services/$serviceId/appointments/export?from=$monday&to=$monday")
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assertThat(csv.lines().first()).contains("Quel est votre besoin ?")
        assertThat(csv).contains("Awa").contains("Binta").contains("Douleur")
        // A phone number stays as it is; only formula signs are neutralized.
        assertThat(csv).contains(",+22460000000").doesNotContain("'+224")
        api
            .get(
                token,
                "/api/v1/services/$serviceId/appointments/export?from=$monday&to=${monday.plusDays(200)}",
            ).andExpect(status().isBadRequest())

        // A refused request takes its answers with it.
        api.post(token, "/api/v1/services/$serviceId/day-line/requests/$bintaRequest/reject", "{}").andExpect(status().is2xxSuccessful())
        assertThat(answerCount(serviceId)).isEqualTo(1)

        // The retention policy removes the answers with the client's name and number.
        retention.anonymizeBefore(Instant.now().plusSeconds(3_600L * 24 * 30))
        assertThat(answerCount(serviceId)).isZero()
        assertThat(awa).isNotBlank()
    }

    @Test
    fun `an answer that leads to another service cannot book this one`() {
        val token = api.register()
        val serviceId = service(token, "Soins")
        val scaling = service(token, "Détartrage")
        api.put(
            token,
            "/api/v1/services/$serviceId/questions",
            """{"questions":[{"prompt":"Besoin ?","choices":[{"label":"Douleur"},{"label":"Détartrage","routeServiceId":"$scaling"}]}]}""",
        )
        val asked =
            api
                .get(token, "/api/v1/services/$serviceId/questions")
                .andReturn()
                .response.contentAsString
        val questionId = JsonPath.read<String>(asked, "$[0].id")
        val routed = JsonPath.read<String>(asked, "$[0].choices[1].id")
        val code = shortCode(token, serviceId)
        val monday = LocalDate.now(ZoneId.of("Africa/Conakry")).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        book(code, slots(code, monday)[0], "Awa", """{"questionId":"$questionId","choiceId":"$routed"}""")
            .andExpect(status().isBadRequest())
    }

    private fun question(
        prompt: String,
        vararg labels: String,
    ): String = """{"prompt":"$prompt","choices":[${labels.joinToString(",") { """{"label":"$it"}""" }}]}"""

    private fun service(
        token: String,
        name: String,
    ): String =
        JsonPath.read(
            api
                .post(
                    token,
                    "/api/v1/services",
                    """{"name":"$name","timezone":"Africa/Conakry","quickSetup":{"durationMinutes":30,"hostName":"Kadiatou"}}""",
                ).andExpect(status().isCreated())
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
                .get(token, "/api/v1/services/$serviceId/share")
                .andReturn()
                .response.contentAsString,
            "$.code",
        )

    private fun slots(
        code: String,
        day: LocalDate,
    ): List<String> =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/r/$code").param("date", day.toString()))
                .andReturn()
                .response.contentAsString,
            "$.slots[*].startsAt",
        )

    private fun book(
        code: String,
        startsAt: String,
        name: String,
        answer: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/r/$code/book")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"clientName":"$name","clientPhone":"+22460000000${name.length}","startsAt":"$startsAt",""" +
                        """"answers":[${answer.ifEmpty { "" }}]}""",
                ),
        )

    private fun bookingToken(result: ResultActions): String =
        JsonPath.read(
            result
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.bookingToken",
        )

    private fun answerCount(serviceId: String): Int =
        requireNotNull(
            jdbc.queryForObject("SELECT COUNT(*) FROM service_booking_answer WHERE service_id = ?::uuid", Int::class.java, serviceId),
        )
}
