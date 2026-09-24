package com.jiku.support

import com.jayway.jsonpath.JsonPath
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
                """{"name":"Test Event","timezone":"Africa/Conakry","startDateTime":"2026-12-01T18:00:00Z","invitationChannels":["EMAIL"]}""",
            ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

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
