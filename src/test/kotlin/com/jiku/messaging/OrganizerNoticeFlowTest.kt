package com.jiku.messaging

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.messaging.internal.OrganizerNoticeService
import com.jiku.support.OrganizerApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * JIKU-314: what billing announces lands in the organization's bell, can be
 * read one by one or all at once, and never shows in another organization's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class OrganizerNoticeFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var notices: OrganizerNoticeService

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `an offered gift reaches the bell and reading it clears the count`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        api.publish(token, eventId)

        api
            .get(token, "/api/v1/notices/unread-count")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unread").value(0))

        grantTrial(api.tenantId(token), eventId, 300)

        val page =
            api
                .get(token, "/api/v1/notices")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].kind").value("gift.OFFERED"))
                .andExpect(jsonPath("$.items[0].params.guests").value("300"))
                .andExpect(jsonPath("$.items[0].link").value("/billing"))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andReturn()
                .response.contentAsString
        val noticeId = JsonPath.read<String>(page, "$.items[0].id")

        api
            .post(token, "/api/v1/notices/$noticeId/read", "{}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.readAt").exists())
        api
            .get(token, "/api/v1/notices/unread-count")
            .andExpect(jsonPath("$.unread").value(0))
    }

    @Test
    fun `an organization never sees nor reads another one's notices`() {
        val owner = api.register()
        val ownerEvent = api.createEvent(owner)
        api.publish(owner, ownerEvent)
        grantTrial(api.tenantId(owner), ownerEvent, 200)
        val noticeId =
            JsonPath.read<String>(
                api
                    .get(owner, "/api/v1/notices")
                    .andReturn()
                    .response.contentAsString,
                "$.items[0].id",
            )

        val stranger = api.register()
        api
            .get(stranger, "/api/v1/notices")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.unread").value(0))
        api.post(stranger, "/api/v1/notices/$noticeId/read", "{}").andExpect(status().isNotFound())
        api.post(stranger, "/api/v1/notices/read-all", "{}").andExpect(status().isOk())

        api
            .get(owner, "/api/v1/notices/unread-count")
            .andExpect(jsonPath("$.unread").value(1))
        api
            .post(owner, "/api/v1/notices/read-all", "{}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.unread").value(0))
        api
            .get(owner, "/api/v1/notices/unread-count")
            .andExpect(jsonPath("$.unread").value(0))
    }

    @Test
    fun `the bell has a live ticket and old notices are removed`() {
        val token = api.register()
        api
            .get(token, "/api/v1/notices/live-ticket")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticket").isNotEmpty())

        val eventId = api.createEvent(token)
        api.publish(token, eventId)
        grantTrial(api.tenantId(token), eventId, 150)

        assertThat(notices.purgeOlderThan(Instant.now().plus(1, ChronoUnit.MINUTES))).isGreaterThanOrEqualTo(1)
        api
            .get(token, "/api/v1/notices")
            .andExpect(jsonPath("$.items").isEmpty())
    }

    @Test
    fun `the bell is for organizers only`() {
        mockMvc
            .perform(get("/api/v1/notices"))
            .andExpect(status().isForbidden())
    }

    private fun grantTrial(
        tenantId: String,
        eventId: String,
        guests: Int,
    ) {
        mockMvc
            .perform(
                post("/api/v1/admin/gifts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"tenantId":"$tenantId","kind":"EVENT_GUESTS","guests":$guests,"days":7,"eventId":"$eventId"}""")
                    .header("Authorization", "Bearer ${adminLogin()}"),
            ).andExpect(status().isOk())
    }

    private fun adminLogin(): String {
        val email = "notices-admin@jiku.test"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode("admin-secret"))))
        }
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"admin-secret"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
