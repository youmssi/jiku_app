package com.jiku.booking

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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * JIKU-98 : la liste d'accès anticipé rendez-vous. La campagne tourne avant que le
 * produit n'existe ; ce point d'entrée est ce qui empêche la dépense publicitaire de
 * ne rien produire de réutilisable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ProspectLeadTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private fun phone() = "+2246${(1000000..9999999).random()}"

    private fun body(
        phone: String,
        business: String = "Atelier Aïcha",
        sector: String = "COUTURE",
    ) = """
        {"businessName":"$business","contactName":"Aïcha Diallo","phone":"$phone",
         "sector":"$sector","city":"Conakry","weeklyVolume":"20-50","source":"fb-sept"}
        """.trimIndent()

    @Test
    fun `un professionnel s'inscrit sans compte`() {
        mockMvc
            .perform(post("/api/v1/prospects").contentType(MediaType.APPLICATION_JSON).content(body(phone())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNotEmpty())
    }

    @Test
    fun `une seconde soumission met la piste à jour au lieu de la dupliquer`() {
        val phone = phone()

        val first =
            mockMvc
                .perform(post("/api/v1/prospects").contentType(MediaType.APPLICATION_JSON).content(body(phone)))
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val second =
            mockMvc
                .perform(
                    post("/api/v1/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(phone, business = "Atelier Aïcha & Filles")),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString

        // Même piste : personne ne devra dédupliquer à la main avant de rappeler.
        assert(JsonPath.read<String>(first, "$.id") == JsonPath.read<String>(second, "$.id"))
    }

    @Test
    fun `un secteur inconnu est accepté comme AUTRE plutôt que rejeté`() {
        // Perdre une piste sur une valeur d'énumération serait absurde : le secteur
        // est déclaratif et sert seulement à trier les rappels.
        mockMvc
            .perform(
                post("/api/v1/prospects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(phone(), sector = "PLOMBERIE")),
            ).andExpect(status().isCreated())
    }

    @Test
    fun `les champs obligatoires sont exigés`() {
        mockMvc
            .perform(
                post("/api/v1/prospects")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"businessName":"","contactName":"","phone":"","sector":"COUTURE"}"""),
            ).andExpect(status().isBadRequest())
    }

    @Test
    fun `la liste des pistes est refusée sans authentification administrateur`() {
        mockMvc.perform(get("/api/v1/admin/prospects")).andExpect(status().is4xxClientError())
    }

    @Test
    fun `marquer contactée une piste inexistante répond 404`() {
        mockMvc
            .perform(post("/api/v1/admin/prospects/${UUID.randomUUID()}/contacted"))
            .andExpect(status().is4xxClientError())
    }
}
