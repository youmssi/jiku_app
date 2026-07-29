package com.jiku.billing

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * JIKU-54: the free tier is a cumulative, per-account budget, not a per-event
 * one — a tenant cannot bypass it by fragmenting guests across many small
 * events, and a fresh tenant still gets the full allowance.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["billing.free-tier-guests=100"])
class CumulativeFreeTierTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `ten events of ten guests consume the cumulative 100 and block the eleventh`() {
        val token = register()

        // Nine events of ten guests each: 90 of the 100 cumulative budget used,
        // every send succeeding despite each event individually looking small.
        repeat(9) { index ->
            val eventId = createPublishedEvent(token, "Frag $index")
            val names = (1..10).map { "Guest$it-$index" to "Last" }
            importGuests(token, eventId, names)
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/invitations/send?channels=EMAIL")
                        .header("Authorization", "Bearer $token"),
                ).andExpect(status().isOk())
        }

        // The tenth event's ten guests reach exactly the 100 cumulative cap.
        val tenthEvent = createPublishedEvent(token, "Frag 9")
        importGuests(token, tenthEvent, (1..10).map { "Guest$it-9" to "Last" })
        mockMvc
            .perform(
                post("/api/v1/events/$tenthEvent/invitations/send?channels=EMAIL")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())

        // The eleventh event's guests would push the cumulative account total
        // past 100 — blocked, even though this specific event has zero guests
        // of its own yet.
        val eleventhEvent = createPublishedEvent(token, "Frag 10")
        importGuests(token, eleventhEvent, listOf("Overflow" to "Guest"))
        mockMvc
            .perform(
                post("/api/v1/events/$eleventhEvent/invitations/send?channels=EMAIL")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isPaymentRequired())
    }

    @Test
    fun `a fresh tenant sees the full free allowance on its first event`() {
        val token = register()
        val eventId = createPublishedEvent(token, "Fresh")
        val usage =
            mockMvc
                .perform(get("/api/v1/events/$eventId/usage").header("Authorization", "Bearer $token"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assert(JsonPath.read<Int>(usage, "$.allowance") == 100)
    }

    private fun importGuests(
        token: String,
        eventId: String,
        rows: List<Pair<String, String>>,
    ) {
        val csv =
            buildString {
                appendLine("firstName,lastName,email,phone")
                rows.forEachIndexed { index, (first, last) ->
                    appendLine("$first,$last,${first.lowercase()}.$index.${eventId.take(6)}@example.com,")
                }
            }
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private fun createPublishedEvent(
        token: String,
        name: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"$name","timezone":"Africa/Abidjan",
                             "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        return eventId
    }

    private fun register(): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Frag Org","email":"frag-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
