package com.jiku.backoffice

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.ErrorPipelineProbeException
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
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
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * La sonde de chaîne d'erreurs (JIKU-97) doit prouver deux choses distinctes :
 * que l'exception atteint réellement le traqueur, et que l'endpoint n'est pas
 * atteignable sans être administrateur de plateforme.
 *
 * Vérifier seulement le 500 ne prouverait rien : c'est justement ce que rend
 * n'importe quelle panne. Ce qui compte est que le traqueur ait été appelé.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, AdminDiagnosticsTest.RecordingTracker::class)
class AdminDiagnosticsTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var tracker: Recording

    @BeforeEach
    fun reset() = tracker.captured.clear()

    @Test
    fun `la sonde transmet l'exception au traqueur et repond 500 avec un requestId`() {
        val token = adminToken()

        val response =
            mockMvc
                .perform(post("/api/v1/admin/diagnostics/error").header("Authorization", "Bearer $token"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.requestId").exists())
                .andReturn()
                .response.contentAsString

        // L'identifiant rendu à l'appelant est celui qu'il doit retrouver dans le
        // tableau de bord : sans lui, impossible de relier les deux bouts.
        val requestId = JsonPath.read<String>(response, "$.requestId")

        assertThat(tracker.captured).hasSize(1)
        assertThat(tracker.captured.single().first).isInstanceOf(ErrorPipelineProbeException::class.java)
        assertThat(tracker.captured.single().second)
            .containsEntry("requestId", requestId)
            .containsEntry("path", "/api/v1/admin/diagnostics/error")
            .containsEntry("method", "POST")
    }

    @Test
    fun `la sonde est fermee sans authentification administrateur`() {
        // 4xx générique, comme dans AdminAccessTest : la sémantique exacte
        // (401 vs 403) dépend de la configuration de l'entry point et ne fait
        // pas partie du contrat de la sonde.
        mockMvc
            .perform(post("/api/v1/admin/diagnostics/error"))
            .andExpect(status().is4xxClientError())

        // Rien ne doit avoir été signalé : un appel refusé n'est pas un incident.
        assertThat(tracker.captured).isEmpty()
    }

    private fun adminToken(): String {
        val email = "diagnostics@jiku.test"
        val password = "diagnostics-secret"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode(password))))
        }
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"$password"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    class Recording : ErrorTracker {
        val captured = mutableListOf<Pair<Throwable, Map<String, String>>>()

        override fun capture(
            throwable: Throwable,
            context: Map<String, String>,
        ) {
            captured += throwable to context
        }
    }

    @TestConfiguration
    class RecordingTracker {
        @Bean
        @Primary
        fun recordingErrorTracker() = Recording()
    }
}
