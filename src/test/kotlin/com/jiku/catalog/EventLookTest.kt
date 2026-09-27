package com.jiku.catalog

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * JIKU-194: the organizer picks the look of an event's guest-facing surfaces,
 * one of three styles, and may add a banner photo. The photo is served publicly
 * from the upload on, and the public views carry both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class EventLookTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a new event is modern and has no photo, and the organizer picks another style`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        api
            .get(token, "/api/v1/events/$eventId/look")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cardStyle").value("MODERN"))
            .andExpect(jsonPath("$.bannerUrl").value(nullValue()))
        api
            .put(token, "/api/v1/events/$eventId/look", """{"cardStyle":"ELEGANT"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cardStyle").value("ELEGANT"))
        api.put(token, "/api/v1/events/$eventId/look", """{"cardStyle":"GOLDEN"}""").andExpect(status().isBadRequest())
        api.put(token, "/api/v1/events/$eventId/look", "{}").andExpect(status().isBadRequest())
    }

    @Test
    fun `a banner photo is stored, served publicly, and removed`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        val photo = png(1200, 600)
        val url =
            JsonPath.read<String>(
                upload(token, eventId, photo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bannerUrl").value(containsString("/api/v1/public/events/$eventId/banner?v=")))
                    .andReturn()
                    .response.contentAsString,
                "$.bannerUrl",
            )
        val path = url.substringAfter("http://localhost:8080")
        mockMvc
            .perform(get(path))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control", containsString("immutable")))
            .andExpect(content().bytes(photo))

        api.publish(token, eventId)
        val code =
            JsonPath.read<String>(
                api
                    .put(token, "/api/v1/events/$eventId/open-invitation", "{}")
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )
        mockMvc
            .perform(get("/api/v1/open/$code"))
            .andExpect(jsonPath("$.cardStyle").value("MODERN"))
            .andExpect(jsonPath("$.bannerUrl").value(startsWith("http://localhost:8080/api/v1/public/events/$eventId/banner")))

        mockMvc
            .perform(delete("/api/v1/events/$eventId/banner").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bannerUrl").value(nullValue()))
        mockMvc.perform(get(path)).andExpect(status().isNotFound())
    }

    @Test
    fun `a banner must be a real photo of a usable size`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        upload(token, eventId, png(400, 200)).andExpect(status().isBadRequest())
        upload(token, eventId, "not an image".toByteArray()).andExpect(status().isBadRequest())
        upload(token, eventId, png(1200, 600), "image/gif").andExpect(status().isUnsupportedMediaType())
    }

    @Test
    fun `another organization cannot see or change the look`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        val other = api.register()
        api.get(other, "/api/v1/events/$eventId/look").andExpect(status().isNotFound())
        api.put(other, "/api/v1/events/$eventId/look", """{"cardStyle":"FESTIVE"}""").andExpect(status().isNotFound())
        upload(other, eventId, png(1200, 600)).andExpect(status().isNotFound())
    }

    private fun upload(
        token: String,
        eventId: String,
        bytes: ByteArray,
        type: String = "image/png",
    ): ResultActions =
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/events/$eventId/banner")
                .file(MockMultipartFile("file", "banner.png", type, bytes))
                .header("Authorization", "Bearer $token"),
        )

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(124, 45, 18)
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
