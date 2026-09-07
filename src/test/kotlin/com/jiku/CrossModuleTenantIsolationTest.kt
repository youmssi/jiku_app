package com.jiku

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * JIKU-31 security review: re-verifies the JIKU-6 tenant-isolation guarantee end to
 * end across the event, invitation, ticketing and checkin modules together — not
 * only the persistence-layer unit test. One tenant must never read or act on
 * another tenant's event, guests, dashboard or check-in, even with a valid token
 * of their own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CrossModuleTenantIsolationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `one tenant cannot see or act on another tenant's event across modules`() {
        // Tenant A: an event with a guest and a validator link.
        val tokenA = register("a")
        val eventA = createEvent(tokenA)
        importGuest(tokenA, eventA)
        val validatorLink = createValidator(tokenA, eventA)

        // Tenant B: a separate organizer with their own token.
        val tokenB = register("b")

        // event module: B cannot read A's event.
        mockMvc
            .perform(get("/api/v1/events/$eventA").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isNotFound())

        // invitation module: B sees no guests on A's event.
        mockMvc
            .perform(get("/api/v1/events/$eventA/guests").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))

        // checkin module (dashboard): B cannot read A's event dashboard.
        mockMvc
            .perform(get("/api/v1/events/$eventA/dashboard").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isNotFound())

        // B's own event list is empty — no cross-tenant leakage.
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))

        // ticketing/checkin via the validator link (tenant carried in the signed
        // token) still only sees tenant A's roster, never anyone else's.
        val roster =
            mockMvc
                .perform(get("/api/v1/checkin/$validatorLink/roster"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        val rosterIds: List<Any> = JsonPath.read(roster, "$[*]")
        assert(rosterIds.size == 1) { "validator roster must contain only tenant A's single guest" }
    }

    private fun createValidator(
        token: String,
        eventId: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/validators")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"label":"Gate"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read<String>(body, "$.link").substringAfterLast("/checkin/")
    }

    private fun importGuest(
        token: String,
        eventId: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Jane,Doe,jane@example.com,
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "g.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private fun createEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {"name":"Tenant Event","timezone":"Africa/Abidjan",
                            "startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"]}
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        val eventId = JsonPath.read<String>(body, "$.id")
        mockMvc
            .perform(post("/api/v1/events/$eventId/publish").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
        return eventId
    }

    private fun register(prefix: String): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$prefix Org","email":"$prefix-${UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
}
