package com.jiku.shared

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.shared.observability.ErrorTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * JIKU-29: correlation ids on every request, and error tracking that captures
 * genuinely unhandled exceptions while leaving expected control-flow errors alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, ObservabilityTest.TestEndpoints::class)
class ObservabilityTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tracker: RecordingErrorTracker

    private lateinit var accessToken: String

    @BeforeEach
    fun setUp() {
        tracker.captured.clear()
        tracker.contexts.clear()
        // A fresh organizer per test: registration is unique-by-email, so reusing
        // one address would make later registrations conflict instead of returning
        // a token.
        val email = "obs-${java.util.UUID.randomUUID()}@test.example"
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Obs Org","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        accessToken = JsonPath.read(body, "$.accessToken")
    }

    @Test
    fun `every request is answered with a correlation id header`() {
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Request-Id"))
    }

    @Test
    fun `an inbound correlation id is honoured`() {
        mockMvc
            .perform(
                get("/api/v1/events")
                    .header("Authorization", "Bearer $accessToken")
                    .header("X-Request-Id", "trace-from-caller"),
            ).andExpect(header().string("X-Request-Id", "trace-from-caller"))
    }

    @Test
    fun `an unhandled exception is reported and answered with 500 plus the correlation id`() {
        mockMvc
            .perform(get("/api/v1/observability-test/boom").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.requestId").isNotEmpty())

        assertThat(tracker.captured).hasSize(1)
        assertThat(tracker.captured.single()).isInstanceOf(IllegalStateException::class.java)
        assertThat(tracker.contexts.single()).containsKey("requestId").containsEntry("method", "GET")
    }

    @Test
    fun `an expected control-flow error keeps its status and is not reported`() {
        mockMvc
            .perform(get("/api/v1/observability-test/conflict").header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isConflict())

        assertThat(tracker.captured).isEmpty()
    }

    /** Records what it was asked to capture, so the tests can assert on it. */
    class RecordingErrorTracker : ErrorTracker {
        val captured = mutableListOf<Throwable>()
        val contexts = mutableListOf<Map<String, String>>()

        override fun capture(
            throwable: Throwable,
            context: Map<String, String>,
        ) {
            captured += throwable
            contexts += context
        }
    }

    @TestConfiguration
    class TestEndpoints {
        @Bean
        @Primary
        fun recordingErrorTracker() = RecordingErrorTracker()

        @Bean
        fun observabilityTestController() = BoomController()
    }

    @RestController
    @RequestMapping("/observability-test")
    class BoomController {
        @GetMapping("/boom")
        fun boom(): Nothing = throw IllegalStateException("boom")

        @GetMapping("/conflict")
        fun conflict(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "expected conflict")
    }
}
