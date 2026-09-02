package com.jiku.backoffice

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Back-office WhatsApp guardrail controls (JIKU-61): pricing is database-backed
 * and editable, and every content-override flip lands in the admin audit log.
 * The admin login flow mirrors [AdminAccessTest].
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminWhatsAppControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `admin can list and update WhatsApp pricing, and the change is audited`() {
        val token = adminToken("whatsapp-pricing-admin@jiku.test", "operator-secret")

        mockMvc
            .perform(get("/api/v1/admin/whatsapp/pricing").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.category == 'UTILITY')].costUsdMinor").value(4))

        mockMvc
            .perform(
                put("/api/v1/admin/whatsapp/pricing/UTILITY")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"costUsdMinor": 6}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.costUsdMinor").value(6))

        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .header("Authorization", "Bearer $token")
                    .param("action", "WHATSAPP_PRICING_UPDATED"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("whatsapp-pricing:UTILITY"))
    }

    @Test
    fun `enabling and disabling the content override is reflected and audited`() {
        val token = adminToken("whatsapp-override-admin@jiku.test", "operator-secret")

        mockMvc
            .perform(
                post("/api/v1/admin/whatsapp/content-override")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"active": true, "reason": "campaign approved by legal"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true))

        mockMvc
            .perform(get("/api/v1/admin/whatsapp/content-override").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.reason").value("campaign approved by legal"))

        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .header("Authorization", "Bearer $token")
                    .param("action", "WHATSAPP_CONTENT_OVERRIDE_ENABLED"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("whatsapp:content-override"))

        // Disable it again so this test does not leak state into others.
        mockMvc
            .perform(
                post("/api/v1/admin/whatsapp/content-override")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"active": false, "reason": "campaign finished"}"""),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false))
    }

    @Test
    fun `an organizer token cannot reach the WhatsApp admin surface`() {
        val organizerToken = registerOrganizer("whatsapp-admin-blocked-org@jiku.test")

        mockMvc
            .perform(get("/api/v1/admin/whatsapp/pricing").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isForbidden())
    }

    private fun adminToken(
        email: String,
        password: String,
    ): String {
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode(password))))
        }
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"$password"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun registerOrganizer(email: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"WhatsApp Admin Blocked Org","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
