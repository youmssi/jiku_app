package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.support.OrganizerApi
import com.jiku.support.TestDates.EVENT_YEAR
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * ADR 105: an event run for a client carries the client's name, logo and
 * colour to its guests instead of the organization's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class EventClientBrandTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `guests see the client's brand, not the organization's`(output: CapturedOutput) {
        val api = OrganizerApi(mockMvc)
        val token = api.register()
        val eventId =
            JsonPath.read<String>(
                api
                    .post(
                        token,
                        "/api/v1/events",
                        """
                        {"name":"Mariage Diallo","timezone":"Africa/Conakry","startDateTime":"${EVENT_YEAR}-12-05T17:00:00Z",
                        "invitationChannels":["WHATSAPP"],
                        "settings":{"deliveryMode":"DIRECT_TICKET","brandName":"Maison Aminata",
                        "brandLogoUrl":"https://cdn.example/aminata.png","brandColor":"#7C2D12"}}
                        """.trimIndent(),
                    ).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.settings.brandName").value("Maison Aminata"))
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        val phone = "+22464" + Random.nextInt(1_000_000, 9_999_999)
        val csv = "firstName,lastName,email,phone\nFatou,Diallo,,$phone"
        mockMvc
            .perform(
                multipart("/api/v1/events/$eventId/guests/import")
                    .file(MockMultipartFile("file", "guests.csv", "text/csv", csv.toByteArray()))
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isOk())

        api.post(token, "/api/v1/events/$eventId/invitations/send?channels=WHATSAPP", "{}").andExpect(status().isOk())
        val rsvpToken =
            await().atMost(10, TimeUnit.SECONDS).until(
                {
                    Regex(
                        Regex.escape("to=$phone buttons=[] image=") + "\\S*/rsvp/([^/\\s]+)/qr\\.png",
                    ).find(output.out)?.groupValues?.get(1)
                },
                { it != null },
            )!!

        mockMvc
            .perform(get("/api/v1/rsvp/$rsvpToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organizerName").value("Maison Aminata"))
            .andExpect(jsonPath("$.logoUrl").value("https://cdn.example/aminata.png"))
            .andExpect(jsonPath("$.primaryColor").value("#7C2D12"))
    }

    @Test
    fun `a brand colour or logo in the wrong format is refused`() {
        val api = OrganizerApi(mockMvc)
        val token = api.register()

        api
            .post(
                token,
                "/api/v1/events",
                """{"name":"Gala","timezone":"Africa/Conakry","settings":{"brandColor":"red"}}""",
            ).andExpect(status().isBadRequest())
        api
            .post(
                token,
                "/api/v1/events",
                """{"name":"Gala","timezone":"Africa/Conakry","settings":{"brandLogoUrl":"http://insecure.example/logo.png"}}""",
            ).andExpect(status().isBadRequest())
    }
}
