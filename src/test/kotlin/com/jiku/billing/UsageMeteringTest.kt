package com.jiku.billing

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.awaitility.Awaitility.await
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
import java.util.concurrent.TimeUnit

/**
 * JIKU-32: usage is metered per event and accumulates correctly across multiple
 * import/send batches, without resetting or double-counting, and the free-tier
 * boundary is reflected in the allowance.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["billing.free-tier-guests=3"])
class UsageMeteringTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `usage accumulates across batches and reflects the free-tier allowance`() {
        val token = register()
        val eventId = createPublishedEvent(token)

        // Batch 1: three guests, invited.
        importGuests(token, eventId, batch = 1, rows = listOf("Ada" to "One", "Bea" to "Two", "Cid" to "Three"))
        sendInvitations(token, eventId)
        awaitInvited(token, eventId, expected = 3)

        var usage = usage(token, eventId)
        // Within the free tier of 3, nothing remaining, still within allowance.
        assert(JsonPath.read<Int>(usage, "$.invitedGuests") == 3)
        assert(JsonPath.read<Int>(usage, "$.allowance") == 3)
        assert(JsonPath.read<Int>(usage, "$.remaining") == 0)
        assert(JsonPath.read<Boolean>(usage, "$.withinAllowance"))
        assert(JsonPath.read<String>(usage, "$.tier") == "FREE")

        // Batch 2: two more guests, invited. Usage must accumulate to 5, not reset.
        importGuests(token, eventId, batch = 2, rows = listOf("Dan" to "Four", "Eve" to "Five"))
        sendInvitations(token, eventId)
        awaitInvited(token, eventId, expected = 5)

        usage = usage(token, eventId)
        assert(JsonPath.read<Int>(usage, "$.invitedGuests") == 5)
        assert(JsonPath.read<Int>(usage, "$.guestsImported") == 5)
        assert(JsonPath.read<Int>(usage, "$.invitationsSentEmail") == 5)
        // Over the free tier now: no remaining, no longer within allowance, paid tier.
        assert(JsonPath.read<Int>(usage, "$.remaining") == 0)
        assert(!JsonPath.read<Boolean>(usage, "$.withinAllowance"))
        assert(JsonPath.read<String>(usage, "$.tier") != "FREE")
    }

    private fun awaitInvited(
        token: String,
        eventId: String,
        expected: Int,
    ) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted {
            assert(JsonPath.read<Int>(usage(token, eventId), "$.invitedGuests") == expected)
        }
    }

    private fun usage(
        token: String,
        eventId: String,
    ): String =
        mockMvc
            .perform(get("/api/v1/events/$eventId/usage").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andReturn()
            .response.contentAsString

    private fun sendInvitations(
        token: String,
        eventId: String,
    ) {
        mockMvc
            .perform(
                post("/api/v1/events/$eventId/invitations/send?channels=EMAIL")
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private fun importGuests(
        token: String,
        eventId: String,
        batch: Int,
        rows: List<Pair<String, String>>,
    ) {
        val csv =
            buildString {
                appendLine("firstName,lastName,email,phone")
                rows.forEach { (first, last) ->
                    appendLine("$first,$last,${first.lowercase()}.b$batch@example.com,")
                }
            }
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private fun createPublishedEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Metered","timezone":"Africa/Abidjan",
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
                            """{"name":"Bill Org","email":"bill-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
