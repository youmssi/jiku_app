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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** JIKU-282: shares, visits and answers of a card, counted per network, and nothing about anyone. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CardFunnelTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `shares, visits and answers add up per network, the most answers first`() {
        val (token, eventId, code) = openCard()
        share(token, eventId, "whatsapp")
        share(token, eventId, "facebook")
        repeat(3) { visit(code, "facebook") }
        visit(code, "whatsapp")
        respond(code, "+224 620 00 00 01", "YES", source = "facebook", companions = 1)
        respond(code, "+224 620 00 00 02", "NO", source = "facebook")
        respond(code, "+224 620 00 00 03", "YES", source = "whatsapp")

        val rows = funnel(token, eventId)

        assertEquals(listOf("facebook", "whatsapp"), JsonPath.read<List<String>>(rows, "$[*].source"))
        assertEquals(listOf(1, 1), JsonPath.read<List<Int>>(rows, "$[*].shares"))
        assertEquals(listOf(3, 1), JsonPath.read<List<Int>>(rows, "$[*].visits"))
        assertEquals(listOf(2, 1), JsonPath.read<List<Int>>(rows, "$[*].answers"))
        assertEquals(listOf(2, 1), JsonPath.read<List<Int>>(rows, "$[*].coming"))
    }

    @Test
    fun `an unknown or missing tag counts as other, and a changed answer keeps its first network`() {
        val (token, eventId, code) = openCard()
        visit(code, null)
        visit(code, "<script>")
        respond(code, "+224 620 00 00 04", "MAYBE", source = "telegram")
        respond(code, "+224 620 00 00 04", "YES", source = "whatsapp")

        val rows = funnel(token, eventId)

        assertEquals(listOf("telegram", "other"), JsonPath.read<List<String>>(rows, "$[*].source"))
        assertEquals(listOf(0, 2), JsonPath.read<List<Int>>(rows, "$[*].visits"))
        assertEquals(listOf(1, 0), JsonPath.read<List<Int>>(rows, "$[*].coming"))
    }

    @Test
    fun `another organization cannot read or feed the funnel`() {
        val (_, eventId, _) = openCard()
        val stranger = api.register()
        api.get(stranger, "/api/v1/events/$eventId/open-invitation/funnel").andExpect(status().isNotFound())
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/open-invitation/shares")
                    .param("source", "whatsapp")
                    .header("Authorization", "Bearer $stranger"),
            ).andExpect(status().isNotFound())
        mockMvc.perform(post("/api/v1/open/NOPENOPE/visits").param("source", "qr")).andExpect(status().isNotFound())
    }

    private fun openCard(): Triple<String, String, String> {
        val token = api.register()
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """{"name":"Birthday","timezone":"Africa/Conakry","startDateTime":"${TestDates.EVENT_YEAR}-12-01T18:00:00Z",""" +
                            """"invitationChannels":["EMAIL"]}""",
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        val code =
            JsonPath.read<String>(
                api
                    .put(token, "/api/v1/events/$eventId/open-invitation", """{"maxCompanions":3}""")
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )
        return Triple(token, eventId, code)
    }

    private fun share(
        token: String,
        eventId: String,
        source: String,
    ) {
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/open-invitation/shares")
                    .param("source", source)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isNoContent())
    }

    private fun visit(
        code: String,
        source: String?,
    ) {
        mockMvc
            .perform(
                post("/api/v1/open/$code/visits")
                    .apply { source?.let { param("source", it) } }
                    .with {
                        it.remoteAddr = randomIp()
                        it
                    },
            ).andExpect(status().isNoContent())
    }

    private fun respond(
        code: String,
        phone: String,
        answer: String,
        source: String,
        companions: Int = 0,
    ) {
        mockMvc
            .perform(
                post("/api/v1/open/$code/responses")
                    .with {
                        it.remoteAddr = randomIp()
                        it
                    }.contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Awa","phone":"$phone","answer":"$answer","companions":$companions,"source":"$source"}"""),
            ).andExpect(status().isOk())
    }

    private fun funnel(
        token: String,
        eventId: String,
    ): String =
        api
            .get(token, "/api/v1/events/$eventId/open-invitation/funnel")
            .andExpect(status().isOk())
            .andReturn()
            .response.contentAsString

    private fun randomIp(): String = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
}
