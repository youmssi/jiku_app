package com.jiku.support

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The organizer-facing calls flow tests keep repeating: sign up, create an event,
 * and send authenticated JSON requests.
 */
class OrganizerApi(
    private val mockMvc: MockMvc,
) {
    /** Signs up a new organizer with an organization and returns its access token. */
    fun register(): String =
        JsonPath.read(
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Test Org","email":"org-${UUID.randomUUID()}@test.example","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.accessToken",
        )

    /** Creates a draft event and returns its id. */
    fun createEvent(token: String): String =
        JsonPath.read(
            post(
                token,
                "/api/v1/events",
                """{"name":"Test Event","timezone":"Africa/Conakry","startDateTime":"${TestDates.EVENT_YEAR}-12-01T18:00:00Z","invitationChannels":["EMAIL"]}""",
            ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    fun publish(
        token: String,
        eventId: String,
    ) {
        post(token, "/api/v1/events/$eventId/publish", "{}").andExpect(status().isOk())
    }

    /** Imports one guest into [eventId] and returns their id. */
    fun importGuest(
        token: String,
        eventId: String,
        firstName: String,
    ): String {
        val csv = "firstName,lastName,email,phone\n$firstName,Test,${firstName.lowercase()}-${UUID.randomUUID()}@test.example,"
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())
        val guests = get(token, "/api/v1/events/$eventId/guests").andReturn().response.contentAsString
        return JsonPath.read<List<String>>(guests, "$[?(@.firstName=='$firstName')].id").first()
    }

    /** The organization the token acts for. */
    fun tenantId(token: String): String = JsonPath.read(get(token, "/api/v1/auth/me").andReturn().response.contentAsString, "$.tenantId")

    fun get(
        token: String,
        path: String,
    ): ResultActions = mockMvc.perform(get(path).header("Authorization", "Bearer $token"))

    fun post(
        token: String,
        path: String,
        json: String,
    ): ResultActions = send(post(path), token, json)

    fun put(
        token: String,
        path: String,
        json: String,
    ): ResultActions = send(put(path), token, json)

    fun patch(
        token: String,
        path: String,
        json: String,
    ): ResultActions = send(patch(path), token, json)

    private fun send(
        request: MockHttpServletRequestBuilder,
        token: String,
        json: String,
    ): ResultActions =
        mockMvc.perform(
            request
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json),
        )
}
