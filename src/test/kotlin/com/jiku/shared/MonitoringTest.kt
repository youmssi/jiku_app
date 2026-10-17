package com.jiku.shared

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import org.awaitility.Awaitility.await
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration

/**
 * JIKU-30: the health probe is public and reflects readiness (including the
 * database), while performance metrics are reserved to the platform team.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class MonitoringTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `health probe is public and reports UP with the database reachable`() {
        // A brief retry, not a hard one-shot assertion, as reasonable defensive
        // hardening for any HTTP-based health check — a genuine app-level DOWN
        // still fails after the retry window instead of being masked.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            mockMvc
                .perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
        }

        // Readiness includes the database connectivity check.
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            mockMvc
                .perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
        }
    }

    @Test
    fun `performance metrics are not exposed to anonymous callers`() {
        mockMvc
            .perform(get("/actuator/metrics/http.server.requests"))
            .andExpect(status().is4xxClientError())
    }

    @Test
    fun `a signed-in organizer cannot read platform metrics`() {
        val token = registerAndToken()
        mockMvc
            .perform(get("/actuator/metrics/http.server.requests").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden())
        mockMvc
            .perform(get("/actuator/prometheus").header("Authorization", "Bearer $token"))
            .andExpect(status().isForbidden())
    }

    @Test
    fun `the platform team reads per-endpoint http request timings`() {
        val token = registerAndToken()
        val admin = adminToken()

        // Generate at least one server request so the timer exists.
        mockMvc.perform(get("/api/v1/events").header("Authorization", "Bearer $token"))

        mockMvc
            .perform(get("/actuator/metrics/http.server.requests").header("Authorization", "Bearer $admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("http.server.requests"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("TOTAL_TIME")))
    }

    private fun adminToken(): String {
        val email = "monitoring@jiku.test"
        val password = "monitoring-secret"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode(password))))
        }
        return JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"$password"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )
    }

    private fun registerAndToken(): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Mon Org","email":"mon-${java.util.UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
