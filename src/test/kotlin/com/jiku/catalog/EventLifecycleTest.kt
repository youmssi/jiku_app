package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.TestDates.EVENT_YEAR
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EventLifecycleTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `events are tenant isolated and move from draft to published`() {
        val tokenA = register("Org A", "org-a-events@test.example")
        val tokenB = register("Org B", "org-b-events@test.example")

        val createBody =
            """{"name":"Gala","timezone":"Africa/Abidjan","startDateTime":"${EVENT_YEAR}-12-31T19:00:00Z","invitationChannels":["EMAIL"]}"""
        val createResult =
            mockMvc
                .perform(authed(post("/api/v1/events"), tokenA).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn()
                .response
                .contentAsString
        val eventId = JsonPath.read<String>(createResult, "$.id")

        // Tenant isolation: owner sees the event, another tenant does not.
        mockMvc.perform(authedGet("/api/v1/events/$eventId", tokenA)).andExpect(status().isOk())
        mockMvc.perform(authedGet("/api/v1/events/$eventId", tokenB)).andExpect(status().isNotFound())
        mockMvc
            .perform(authedGet("/api/v1/events", tokenB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0))

        // Publish succeeds: has name, start time and a channel.
        mockMvc
            .perform(authed(post("/api/v1/events/$eventId/publish"), tokenA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))

        // A draft without an invitation channel cannot be published.
        val noChannel = """{"name":"No Channel","timezone":"Africa/Abidjan","startDateTime":"${EVENT_YEAR}-12-31T19:00:00Z"}"""
        val secondId =
            JsonPath.read<String>(
                mockMvc
                    .perform(authed(post("/api/v1/events"), tokenA).content(noChannel))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .response
                    .contentAsString,
                "$.id",
            )
        mockMvc
            .perform(authed(post("/api/v1/events/$secondId/publish"), tokenA))
            .andExpect(status().isUnprocessableEntity())
    }

    private fun authed(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        token: String,
    ) = builder.header("Authorization", "Bearer $token").contentType(MediaType.APPLICATION_JSON)

    private fun authedGet(
        path: String,
        token: String,
    ) = get(path).header("Authorization", "Bearer $token")

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
