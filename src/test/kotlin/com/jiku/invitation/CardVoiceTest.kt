package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.acceptVoice
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals

/** JIKU-284: the host's voice message, checked by its bytes, played on demand, removable. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CardVoiceTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a recording is recognised by its bytes, not by its name`() {
        assertEquals("audio/webm", acceptVoice(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2), 1_000))
        assertEquals("audio/ogg", acceptVoice("OggS....".toByteArray(), 1_000))
        assertEquals("audio/mp4", acceptVoice("....ftypM4A ".toByteArray(), 1_000))
        assertEquals("audio/mpeg", acceptVoice("ID3.....".toByteArray(), 1_000))
        assertThrows<ResponseStatusException> { acceptVoice("<script>alert(1)</script>".toByteArray(), 1_000) }
        assertThrows<ResponseStatusException> { acceptVoice("OggS".toByteArray() + ByteArray(2_000), 1_000) }
    }

    @Test
    fun `the host records a message, guests play it, and the host removes it`() {
        val (token, eventId, code) = openCard()
        val recording = "OggS".toByteArray() + ByteArray(4_000) { (it % 251).toByte() }

        upload(token, eventId, recording, duration = 24, transcript = "Venez nombreux !")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.durationSeconds").value(24))
            .andExpect(jsonPath("$.transcript").value("Venez nombreux !"))
        val page =
            mockMvc
                .perform(get("/api/v1/open/$code"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        assertEquals(24, JsonPath.read<Int>(page, "$.voice.durationSeconds"))
        mockMvc
            .perform(get("/api/v1/open/$code/voice"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "audio/ogg"))
            .andExpect(content().bytes(recording))

        mockMvc
            .perform(delete("/api/v1/events/$eventId/open-invitation/voice").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent())
        mockMvc.perform(get("/api/v1/open/$code")).andExpect(jsonPath("$.voice").doesNotExist())
        mockMvc.perform(get("/api/v1/open/$code/voice")).andExpect(status().isNotFound())
    }

    @Test
    fun `a message too long, unreadable or from another organization is refused`() {
        val (token, eventId, _) = openCard()
        upload(token, eventId, "OggS....".toByteArray(), duration = 45).andExpect(status().isBadRequest())
        upload(token, eventId, "not audio".toByteArray(), duration = 10).andExpect(status().isUnsupportedMediaType())
        upload(api.register(), eventId, "OggS....".toByteArray(), duration = 10).andExpect(status().isNotFound())
    }

    private fun upload(
        token: String,
        eventId: String,
        bytes: ByteArray,
        duration: Int,
        transcript: String? = null,
    ) = mockMvc.perform(
        multipart(HttpMethod.PUT, "/api/v1/events/$eventId/open-invitation/voice")
            .file(MockMultipartFile("file", "voice.ogg", "audio/ogg", bytes))
            .param("durationSeconds", duration.toString())
            .apply { transcript?.let { param("transcript", it) } }
            .header("Authorization", "Bearer $token"),
    )

    private fun openCard(): Triple<String, String, String> {
        val token = api.register()
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """{"name":"Baptême","timezone":"Africa/Conakry","startDateTime":"${TestDates.EVENT_YEAR}-12-01T18:00:00Z",""" +
                            """"invitationChannels":["EMAIL"]}""",
                    ).andExpect(status().isCreated())
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        val code =
            JsonPath.read<String>(
                api
                    .put(token, "/api/v1/events/$eventId/open-invitation", """{"maxCompanions":3}""")
                    .andExpect(status().isOk())
                    .andReturn()
                    .response.contentAsString,
                "$.code",
            )
        return Triple(token, eventId, code)
    }
}
