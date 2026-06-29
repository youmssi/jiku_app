package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GuestImportTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `imports valid rows and reports failures, duplicates and warnings`() {
        val token = register("Org CSV", "csv@test.example")
        val eventId = createEvent(token)

        val csv =
            """
            firstName,lastName,email,phone
            Ada,Lovelace,ada@example.com,+2250700000001
            Grace,Hopper,,+2250700000002
            Dispo,Sable,user@mailinator.com,
            ,Missing,nomail@example.com,
            Bad,Email,not-an-email,
            Ada,Dup,ada@example.com,
            """.trimIndent()
        val file = MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray())

        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(file)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.imported").value(3))
            .andExpect(jsonPath("$.failed").value(2))
            .andExpect(jsonPath("$.skippedDuplicates").value(1))
            .andExpect(jsonPath("$.warnings.length()").value(1))

        mockMvc
            .perform(get("/api/v1/events/$eventId/guests").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(3))
    }

    private fun createEvent(token: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/events")
                        .header("Authorization", "Bearer $token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Gala","timezone":"Africa/Abidjan"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.id")
    }

    private fun register(
        name: String,
        email: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
