package com.jiku.backoffice

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.support.TestDates.EVENT_YEAR
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JIKU-202: who the platform team should call, and how far new organizations get. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class FollowUpTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    private val threeDaysAgo get() = Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS))

    @Test
    fun `an organization idle for two days is listed, with its owner and consent`() {
        val (token, tenantId) = register(consent = true)
        jdbc.update("UPDATE tenant SET created_at = ? WHERE id = CAST(? AS UUID)", threeDaysAgo, tenantId)

        val entry = entries().single { it["tenantId"] == tenantId }
        assertEquals("NO_ACTIVITY", entry["reason"])
        assertEquals(true, entry["marketingConsent"])
        assertTrue(entry["ownerEmail"].toString().startsWith("follow-"))
        assertTrue(token.isNotBlank())
    }

    @Test
    fun `a fresh organization is not listed yet`() {
        val (_, tenantId) = register()
        assertTrue(entries().none { it["tenantId"] == tenantId })
    }

    @Test
    fun `an event without guests for two days replaces the idle reason`() {
        val (token, tenantId) = register()
        val eventId = createEvent(token)
        jdbc.update("UPDATE tenant SET created_at = ? WHERE id = CAST(? AS UUID)", threeDaysAgo, tenantId)
        jdbc.update("UPDATE event SET created_at = ? WHERE id = CAST(? AS UUID)", threeDaysAgo, eventId)

        val reasons = entries().filter { it["tenantId"] == tenantId }.map { it["reason"] }
        assertEquals(listOf("NO_GUESTS"), reasons)
    }

    @Test
    fun `a refused verification and a trial ending without payment are listed`() {
        val (token, tenantId) = register()
        val eventId = createEvent(token)
        jdbc.update(
            "INSERT INTO organization_verification (id, tenant_id, kind, status, legal_name, document_type, submitted_at) " +
                "VALUES (?, ?, 'PERSONAL', 'REJECTED', 'Awa Diop', 'ID_CARD', now())",
            UUID.randomUUID(),
            tenantId,
        )
        jdbc.update(
            "INSERT INTO trial_grant (id, tenant_id, event_id, tier, granted_allowance, expires_at, status, created_at, updated_at) " +
                "VALUES (?, ?, CAST(? AS UUID), 'STANDARD', 100, ?, 'ACTIVE', now(), now())",
            UUID.randomUUID(),
            tenantId,
            eventId,
            Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)),
        )

        val reasons = entries().filter { it["tenantId"] == tenantId }.map { it["reason"] }.toSet()
        assertEquals(setOf("VERIFICATION_REJECTED", "TRIAL_ENDING"), reasons)
    }

    @Test
    fun `a follow-up marked as done leaves the list`() {
        val (_, tenantId) = register()
        jdbc.update("UPDATE tenant SET created_at = ? WHERE id = CAST(? AS UUID)", threeDaysAgo, tenantId)
        val admin = adminLogin()

        mockMvc
            .perform(post("/api/v1/admin/follow-ups/$tenantId/NO_ACTIVITY/done").header("Authorization", "Bearer $admin"))
            .andExpect(status().isNoContent())

        assertTrue(entries().none { it["tenantId"] == tenantId })
    }

    @Test
    fun `the funnel counts new organizations and their first event`() {
        val (token, _) = register()
        createEvent(token)
        val body = overview()
        val signedUp = JsonPath.read<Int>(body, "$.funnel.signedUp")
        val withActivity = JsonPath.read<Int>(body, "$.funnel.firstEventOrService")
        assertTrue(signedUp >= 1)
        assertTrue(withActivity in 1..signedUp)
    }

    @Test
    fun `only platform admins see the list`() {
        val (token, _) = register()
        mockMvc
            .perform(get("/api/v1/admin/follow-ups").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden())
    }

    private fun entries(): List<Map<String, Any?>> = JsonPath.read(overview(), "$.entries")

    private fun overview(): String =
        mockMvc
            .perform(get("/api/v1/admin/follow-ups").header("Authorization", "Bearer ${adminLogin()}"))
            .andExpect(status().isOk())
            .andReturn()
            .response.contentAsString

    private fun register(consent: Boolean = false): Pair<String, String> {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Follow Org","email":"follow-${UUID.randomUUID()}@test.example",
                               "password":"supersecret","marketingConsent":$consent}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val token = JsonPath.read<String>(body, "$.accessToken")
        val me =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString
        return token to JsonPath.read(me, "$.tenantId")
    }

    private fun createEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Follow Event","timezone":"Africa/Conakry",
                               "startDateTime":"$EVENT_YEAR-12-01T18:00:00Z","invitationChannels":["EMAIL"]}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.id")
    }

    private fun adminLogin(): String {
        val email = "follow-up-admin@jiku.test"
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
