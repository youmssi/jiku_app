package com.jiku.messaging

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MessagingProviderResolver
import com.jiku.money.internal.OwnWhatsAppNumberService
import com.jiku.money.internal.WhatsAppNumberAddonRepository
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Organizer-facing tenant provider settings (JIKU-44): masked reads, encrypted
 * writes, revert-to-platform-default, per-tenant isolation, and validation.
 * The `/test` endpoint's live-delivery path is exercised only for the
 * unconfigured (platform-default, log transport) case — a configured tenant
 * provider would need a real outbound call to Resend/Meta, which these tests
 * do not perform. An organization's own WhatsApp number needs an offer that
 * includes it (ADR 105).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ProviderSettingsTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var ownNumber: OwnWhatsAppNumberService

    @Autowired
    lateinit var addons: WhatsAppNumberAddonRepository

    @Autowired
    lateinit var resolver: MessagingProviderResolver

    @AfterEach
    fun clearTenant() {
        TenantContext.clear()
    }

    @Test
    fun `overview defaults to the platform provider for a fresh tenant`() {
        val token = register("Fresh Org", "fresh@acme.test")

        mockMvc
            .perform(get("/api/v1/settings/providers").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email.configured").value(false))
            .andExpect(jsonPath("$.whatsapp.configured").value(false))
    }

    @Test
    fun `saving an email provider masks the key on every subsequent read`() {
        val token = register("Mail Org", "mail@acme.test")

        mockMvc
            .perform(
                put("/api/v1/settings/providers/email")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"apiKey":"re_live_abcd1234","from":"noreply@mailorg.test","fromName":"Mail Org"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.email.configured").value(true))
            .andExpect(jsonPath("$.email.provider").value("RESEND"))
            .andExpect(jsonPath("$.email.apiKeyMasked").value("••••1234"))
            .andExpect(
                jsonPath("$.email.apiKeyMasked", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("re_live_abcd1234"))),
            )

        mockMvc
            .perform(get("/api/v1/settings/providers").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email.from").value("noreply@mailorg.test"))
            .andExpect(jsonPath("$.email.apiKeyMasked").value("••••1234"))
    }

    @Test
    fun `saving a whatsapp provider needs an offer with the own number, masks the token and keeps template fields`() {
        val token = register("Wa Org", "wa@acme.test")
        val body =
            """{"accessToken":"EAAxxxxxxxx9999","phoneNumberId":"123456789012345",""" +
                """"templateName":"event_invitation","templateLanguage":"fr"}"""
        saveWhatsApp(token, body).andExpect(status().isPaymentRequired())

        val tenantId = tenantId(token)
        TenantContext.set(tenantId)
        ownNumber.confirm(1)
        TenantContext.clear()

        saveWhatsApp(token, body)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.whatsapp.configured").value(true))
            .andExpect(jsonPath("$.whatsapp.allowed").value(true))
            .andExpect(jsonPath("$.whatsapp.provider").value("META_CLOUD"))
            .andExpect(jsonPath("$.whatsapp.phoneNumberId").value("123456789012345"))
            .andExpect(jsonPath("$.whatsapp.accessTokenMasked").value("••••9999"))
            .andExpect(jsonPath("$.whatsapp.templateName").value("event_invitation"))
        TenantContext.set(tenantId)
        assertEquals(true, resolver.whatsApp().tenantOverride)

        addons.findFirstByOrderByExpiresAtDesc()!!.let {
            it.expiresAt = Instant.now().minusSeconds(60)
            addons.saveAndFlush(it)
        }
        assertEquals(false, resolver.whatsApp().tenantOverride, "a lapsed add-on sends through the platform number")
        TenantContext.clear()
        mockMvc
            .perform(get("/api/v1/settings/providers").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$.whatsapp.configured").value(true))
            .andExpect(jsonPath("$.whatsapp.allowed").value(false))
    }

    private fun saveWhatsApp(
        token: String,
        body: String,
    ) = mockMvc.perform(
        put("/api/v1/settings/providers/whatsapp")
            .header("Authorization", "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    )

    private fun tenantId(token: String): String =
        JsonPath.read(
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString,
            "$.tenantId",
        )

    @Test
    fun `removing a configured provider reverts the tenant to the platform default`() {
        val token = register("Revert Org", "revert@acme.test")
        mockMvc
            .perform(
                put("/api/v1/settings/providers/email")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"apiKey":"re_live_zzzz","from":"noreply@revert.test"}"""),
            ).andExpect(status().isOk())

        mockMvc
            .perform(delete("/api/v1/settings/providers/EMAIL").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email.configured").value(false))
    }

    @Test
    fun `a test send against an unconfigured channel uses the platform default`() {
        val token = register("Test Send Org", "testsend@acme.test")

        mockMvc
            .perform(
                post("/api/v1/settings/providers/EMAIL/test")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"recipient":"guest@example.test"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.delivered").value(true))
            .andExpect(jsonPath("$.usingTenantProvider").value(false))
    }

    @Test
    fun `an unknown channel is rejected`() {
        val token = register("Bad Channel Org", "badchannel@acme.test")

        mockMvc
            .perform(delete("/api/v1/settings/providers/SMS").header("Authorization", "Bearer $token"))
            .andExpect(status().isBadRequest())
    }

    @Test
    fun `missing required fields are rejected before anything is persisted`() {
        val token = register("Invalid Org", "invalid@acme.test")

        mockMvc
            .perform(
                put("/api/v1/settings/providers/email")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"apiKey":"","from":"not-an-email"}"""),
            ).andExpect(status().isBadRequest())

        mockMvc
            .perform(get("/api/v1/settings/providers").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email.configured").value(false))
    }

    @Test
    fun `one tenant's provider settings are invisible to another tenant`() {
        val tokenA = register("Isolated Org A", "isoA@acme.test")
        val tokenB = register("Isolated Org B", "isoB@acme.test")

        mockMvc
            .perform(
                put("/api/v1/settings/providers/email")
                    .header("Authorization", "Bearer $tokenA")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"apiKey":"re_live_orgA","from":"noreply@orga.test"}"""),
            ).andExpect(status().isOk())

        mockMvc
            .perform(get("/api/v1/settings/providers").header("Authorization", "Bearer $tokenB"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email.configured").value(false))
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
