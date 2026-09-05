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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Configuration d'un service par son organisateur (JIKU-89) : GET/PUT
 * /services/{id}/configuration expose les options effectives et applique une mise
 * à jour partielle — canal de rappel et décalages compris.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ServiceConfigurationControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `an organizer reads defaults then updates the reminder policy`() {
        val accessToken = register()
        val serviceId = createService(accessToken)
        val path = "/api/v1/services/$serviceId/configuration"

        // Défauts : rappels inactifs, décalages produits par défaut.
        mockMvc
            .perform(get(path).header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reminderChannel").value("NONE"))
            .andExpect(jsonPath("$.reminderOffsetsMinutes[0]").value(1440))
            .andExpect(jsonPath("$.reminderOffsetsMinutes[1]").value(120))
            .andExpect(jsonPath("$.confirmationMode").value("ON_REQUEST"))

        // Activation : canal WhatsApp et décalages personnalisés.
        mockMvc
            .perform(
                put(path)
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reminderChannel":"WHATSAPP","reminderOffsetsMinutes":[60,30]}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.reminderChannel").value("WHATSAPP"))
            .andExpect(jsonPath("$.reminderOffsetsMinutes[0]").value(60))
            .andExpect(jsonPath("$.reminderOffsetsMinutes[1]").value(30))

        // La lecture suivante reflète la valeur persistée.
        mockMvc
            .perform(get(path).header("Authorization", "Bearer $accessToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reminderChannel").value("WHATSAPP"))

        // Désactivation.
        mockMvc
            .perform(
                put(path)
                    .header("Authorization", "Bearer $accessToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reminderChannel":"NONE"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.reminderChannel").value("NONE"))
    }

    private fun register(): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Config Organizer","email":"config-${
                                System.nanoTime()
                            }@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun createService(accessToken: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/services")
                        .header("Authorization", "Bearer $accessToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Coupe","timezone":"Africa/Conakry"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.id")
    }
}
