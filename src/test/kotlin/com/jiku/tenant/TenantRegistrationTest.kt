package com.jiku.tenant

import com.jiku.TestcontainersConfiguration
import com.jiku.tenant.internal.OrganizerUser
import com.jiku.tenant.internal.OrganizerUserRepository
import com.jiku.tenant.internal.TenantRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Covers the JIKU-10 Definition of Done: a registration creates a tenant and its
 * organizer atomically, and a failure partway through rolls the whole thing back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TenantRegistrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tenants: TenantRepository

    @Autowired
    lateinit var users: OrganizerUserRepository

    @BeforeEach
    @AfterEach
    fun reset() {
        users.deleteAll()
        tenants.deleteAll()
    }

    @Test
    fun `registration atomically creates a tenant and its first organizer`() {
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Acme Events","email":"owner@acme.test","password":"supersecret"}"""),
            ).andExpect(status().isCreated())

        assertEquals(1, tenants.count(), "exactly one tenant should exist")
        assertEquals(1, users.count(), "exactly one organizer should exist")
        val tenant = tenants.findAll().first()
        val user = users.findAll().first()
        assertEquals(tenant.id.toString(), user.tenantId, "the organizer must belong to the new tenant")
        assertEquals("owner@acme.test", tenant.contactEmail)
    }

    @Test
    fun `a failure creating the organizer rolls back the tenant`() {
        // A pre-existing user with the same email forces the organizer insert to fail
        // after the tenant row has already been written within the same transaction.
        users.saveAndFlush(
            OrganizerUser(
                tenantId = UUID.randomUUID().toString(),
                email = "taken@acme.test",
                passwordHash = "irrelevant",
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Acme Events","email":"taken@acme.test","password":"supersecret"}"""),
            ).andExpect(status().isConflict())

        assertEquals(0, tenants.count(), "the tenant created mid-transaction must be rolled back")
        assertEquals(1, users.count(), "only the pre-existing organizer should remain")
    }
}
