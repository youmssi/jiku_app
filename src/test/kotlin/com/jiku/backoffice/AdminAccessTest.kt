package com.jiku.backoffice

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.AdminBootstrap
import com.jiku.backoffice.internal.AdminBootstrapProperties
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Platform-admin access control (JIKU-40): the admin surface and the organizer
 * surface are mutually exclusive, and the bootstrap is idempotent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AdminAccessTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Test
    fun `admin can log in and reach the admin surface but not organizer endpoints`() {
        ensureAdmin("operator@jiku.test", "operator-secret")

        // Wrong password is rejected.
        mockMvc
            .perform(
                post("/api/v1/admin/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"operator@jiku.test","password":"wrong"}"""),
            ).andExpect(status().isUnauthorized())

        val adminToken = adminLogin("operator@jiku.test", "operator-secret")

        mockMvc
            .perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries").isArray())

        // An admin token carries no tenant and must not reach organizer endpoints.
        mockMvc
            .perform(get("/api/v1/events").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isForbidden())
    }

    @Test
    fun `organizer token cannot reach the admin surface`() {
        val organizerToken = registerOrganizer("directory-org@jiku.test")

        mockMvc
            .perform(get("/api/v1/admin/tenants").header("Authorization", "Bearer $organizerToken"))
            .andExpect(status().isForbidden())

        // Unauthenticated requests are rejected too.
        mockMvc
            .perform(get("/api/v1/admin/tenants"))
            .andExpect(status().is4xxClientError())
    }

    @Test
    fun `bootstrap creates the first admin once and never overwrites it`() {
        val bootstrap =
            AdminBootstrap(
                admins,
                passwordEncoder,
                AdminBootstrapProperties(email = "bootstrap@jiku.test", password = "first-password"),
            )
        bootstrap.run(DefaultApplicationArguments())
        val created = requireNotNull(admins.findByEmail("bootstrap@jiku.test"))

        // A second run — even with a different configured password — changes nothing.
        AdminBootstrap(
            admins,
            passwordEncoder,
            AdminBootstrapProperties(email = "bootstrap@jiku.test", password = "second-password"),
        ).run(DefaultApplicationArguments())

        val after = requireNotNull(admins.findByEmail("bootstrap@jiku.test"))
        assertThat(after.id).isEqualTo(created.id)
        assertThat(passwordEncoder.matches("first-password", after.passwordHash)).isTrue()

        // Blank configuration is a no-op.
        AdminBootstrap(admins, passwordEncoder, AdminBootstrapProperties()).run(DefaultApplicationArguments())
    }

    private fun ensureAdmin(
        email: String,
        password: String,
    ) {
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode(password))))
        }
    }

    private fun adminLogin(
        email: String,
        password: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"$password"}"""),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
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
                        .content("""{"name":"Directory Org","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
