package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
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
 * JIKU-259: the organizer uploads the organization's logo and banner instead of
 * hosting them elsewhere. The photo is served publicly from the upload on, the
 * branding points at it, and removing it clears the branding.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class BrandingImageTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `an uploaded logo is served publicly, set on the branding, and removed`() {
        val token = api.register()
        val logo = png(512, 512)
        val url =
            JsonPath.read<String>(
                upload(token, "logo", logo)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.logoUrl").value(containsString("/api/v1/public/orgs/")))
                    .andExpect(jsonPath("$.logoUrl").value(containsString("/images/logo?v=")))
                    .andReturn()
                    .response.contentAsString,
                "$.logoUrl",
            )
        val path = url.substringAfter("http://localhost:8080")
        mockMvc
            .perform(get(path))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control", containsString("immutable")))
            .andExpect(content().bytes(logo))
        api.get(token, "/api/v1/branding").andExpect(jsonPath("$.logoUrl").value(url))

        mockMvc
            .perform(delete("/api/v1/branding/logo").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.logoUrl").value(nullValue()))
        mockMvc.perform(get(path)).andExpect(status().isNotFound())
    }

    @Test
    fun `a banner is kept apart from the logo`() {
        val token = api.register()
        upload(token, "logo", png(256, 256)).andExpect(status().isOk())
        upload(token, "banner", png(1200, 300))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bannerUrl").value(containsString("/images/banner?v=")))
            .andExpect(jsonPath("$.logoUrl").value(containsString("/images/logo?v=")))
    }

    @Test
    fun `a photo must be a real image of a usable size`() {
        val token = api.register()
        upload(token, "logo", png(64, 64)).andExpect(status().isBadRequest())
        upload(token, "banner", png(400, 100)).andExpect(status().isBadRequest())
        upload(token, "logo", "not an image".toByteArray()).andExpect(status().isBadRequest())
        upload(token, "logo", png(512, 512), "image/gif").andExpect(status().isUnsupportedMediaType())
        upload(token, "avatar", png(512, 512)).andExpect(status().isNotFound())
    }

    @Test
    fun `each organization only replaces its own photo`() {
        val token = api.register()
        val other = api.register()
        val mine = png(300, 300)
        val url =
            JsonPath.read<String>(
                upload(token, "logo", mine).andReturn().response.contentAsString,
                "$.logoUrl",
            )
        upload(other, "logo", png(400, 400)).andExpect(status().isOk())
        mockMvc.perform(get(url.substringAfter("http://localhost:8080"))).andExpect(content().bytes(mine))
    }

    private fun upload(
        token: String,
        kind: String,
        bytes: ByteArray,
        type: String = "image/png",
    ): ResultActions =
        mockMvc.perform(
            multipart(HttpMethod.PUT, "/api/v1/branding/$kind")
                .file(MockMultipartFile("file", "photo.png", type, bytes))
                .header("Authorization", "Bearer $token"),
        )

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(30, 41, 59)
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
