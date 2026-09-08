package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Questions personnalisées d'un événement (JIKU-77) : l'organisateur les crée et
 * les liste pour son événement, borné par le chemin ; une suppression retire la
 * question. L'enregistrement des réponses et leur export arrivent dans la tranche
 * suivante.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EventQuestionTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `organizer creates, lists and deletes custom questions for their event`() {
        val token = register()
        val eventId = createEvent(token)

        val created =
            mockMvc
                .perform(
                    post("/api/v1/events/$eventId/questions")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"prompt":"Menu : poisson ou viande ?","required":true,"position":1}"""),
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.prompt").value("Menu : poisson ou viande ?"))
                .andExpect(jsonPath("$.required").value(true))
                .andReturn()
                .response.contentAsString
        val questionId = JsonPath.read<String>(created, "$.id")

        mockMvc
            .perform(get("/api/v1/events/$eventId/questions").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].prompt").value("Menu : poisson ou viande ?"))

        mockMvc
            .perform(delete("/api/v1/events/$eventId/questions/$questionId").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent())

        mockMvc
            .perform(get("/api/v1/events/$eventId/questions").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `question cannot be created for a missing event`() {
        val token = register()
        mockMvc
            .perform(
                post("/api/v1/events/${UUID.randomUUID()}/questions")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"prompt":"Menu ?"}"""),
            ).andExpect(status().isNotFound())
    }

    private fun register(): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Q Org","email":"q-${UUID.randomUUID()}@test.example","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )

    private fun createEvent(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Gala","timezone":"Africa/Abidjan"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )
}
