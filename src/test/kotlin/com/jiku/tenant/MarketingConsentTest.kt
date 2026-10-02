package com.jiku.tenant

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JIKU-201: consent to Jikū's news and tips is never assumed and can be withdrawn. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class MarketingConsentTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun email() = "consent-${UUID.randomUUID()}@example.com"

    private fun register(
        email: String,
        consentFields: String = "",
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"supersecret"$consentFields}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun stored(email: String): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT marketing_consent, marketing_consent_at, marketing_consent_version, marketing_consent_source " +
                "FROM organizer_user WHERE email = ?",
            email,
        )

    @Test
    fun `an unchecked box records no consent`() {
        val email = email()
        val token = register(email)

        mockMvc
            .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.marketingConsent").value(false))
        val row = stored(email)
        assertEquals(false, row["marketing_consent"])
        assertNull(row["marketing_consent_at"])
        assertNull(row["marketing_consent_source"])
    }

    @Test
    fun `a checked box records the consent with its proof`() {
        val email = email()
        val token = register(email, ""","marketingConsent":true,"marketingConsentVersion":"2026-10-02"""")

        mockMvc
            .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$.marketingConsent").value(true))
        val row = stored(email)
        assertEquals(true, row["marketing_consent"])
        assertNotNull(row["marketing_consent_at"])
        assertEquals("2026-10-02", row["marketing_consent_version"])
        assertEquals("signup", row["marketing_consent_source"])
    }

    @Test
    fun `consent withdrawn in the settings is recorded with its date`() {
        val email = email()
        val token = register(email, ""","marketingConsent":true,"marketingConsentVersion":"2026-10-02"""")

        mockMvc
            .perform(
                put("/api/v1/auth/me/marketing-consent")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"granted":false,"textVersion":"2026-10-02"}"""),
            ).andExpect(status().isNoContent())

        mockMvc
            .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
            .andExpect(jsonPath("$.marketingConsent").value(false))
        val row = stored(email)
        assertFalse(row["marketing_consent"] as Boolean)
        assertNotNull(row["marketing_consent_at"])
        assertEquals("settings", row["marketing_consent_source"])
    }

    @Test
    fun `consent can be given later from the settings`() {
        val email = email()
        val token = register(email)

        mockMvc
            .perform(
                put("/api/v1/auth/me/marketing-consent")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"granted":true,"textVersion":"2026-10-02"}"""),
            ).andExpect(status().isNoContent())

        val row = stored(email)
        assertTrue(row["marketing_consent"] as Boolean)
        assertEquals("settings", row["marketing_consent_source"])
    }

    @Test
    fun `changing consent requires a signed-in account`() {
        mockMvc
            .perform(
                put("/api/v1/auth/me/marketing-consent")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"granted":true}"""),
            ).andExpect(status().is4xxClientError())
    }
}
