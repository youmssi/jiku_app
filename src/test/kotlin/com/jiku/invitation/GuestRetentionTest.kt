package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.GuestRepository
import com.jiku.invitation.internal.GuestRetentionJob
import com.jiku.shared.TenantContext
import org.assertj.core.api.Assertions.assertThat
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
import java.util.UUID

/**
 * JIKU-37: the scheduled retention job anonymizes guests of an event past its
 * retention window, without manual intervention, reusing the JIKU-36 mechanism.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["compliance.retention.days=1"])
class GuestRetentionTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var retentionJob: GuestRetentionJob

    @Autowired
    lateinit var guests: GuestRepository

    @Test
    fun `guests of an event past its retention window are anonymized by the job`() {
        val token = register()
        val tenantId = tenantId(token)
        // An event whose date is two days past — beyond the one-day test retention.
        val pastEventId = createEvent(token, "Past Event", endDaysAgo = 2)
        importGuest(token, pastEventId, "past@example.com")
        val pastGuestId = firstGuestId(token, pastEventId)

        // A future event's guests must be left untouched.
        val futureEventId = createEvent(token, "Future Event", endDaysAgo = -30)
        importGuest(token, futureEventId, "future@example.com")
        val futureGuestId = firstGuestId(token, futureEventId)

        val anonymized = retentionJob.anonymizePastEvents()
        assertThat(anonymized).isGreaterThanOrEqualTo(1)

        TenantContext.set(tenantId)
        try {
            val pastGuest = guests.findById(UUID.fromString(pastGuestId)).orElseThrow()
            assertThat(pastGuest.personalDataErased).isTrue()
            assertThat(pastGuest.email).isNull()
            assertThat(pastGuest.firstName).isEqualTo("Deleted")

            val futureGuest = guests.findById(UUID.fromString(futureGuestId)).orElseThrow()
            assertThat(futureGuest.personalDataErased).isFalse()
            assertThat(futureGuest.email).isEqualTo("future@example.com")
        } finally {
            TenantContext.clear()
        }
    }

    private fun createEvent(
        token: String,
        name: String,
        endDaysAgo: Long,
    ): String {
        val start =
            java.time.Instant
                .now()
                .minus(java.time.Duration.ofDays(endDaysAgo + 1))
        val end =
            java.time.Instant
                .now()
                .minus(java.time.Duration.ofDays(endDaysAgo))
        return JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"$name","timezone":"Africa/Abidjan","startDateTime":"$start","endDateTime":"$end"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )
    }

    private fun importGuest(
        token: String,
        eventId: String,
        email: String,
    ) {
        val csv =
            """
            firstName,lastName,email,phone
            Jane,Doe,$email,
            """.trimIndent()
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
    }

    private fun firstGuestId(
        token: String,
        eventId: String,
    ): String {
        val ids: List<String> =
            JsonPath.read(
                mockMvc
                    .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
                    .andReturn()
                    .response.contentAsString,
                "$[*].id",
            )
        return ids.first()
    }

    private fun tenantId(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString,
            "$.tenantId",
        )

    private fun register(): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Ret Org","email":"ret-${UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
}
