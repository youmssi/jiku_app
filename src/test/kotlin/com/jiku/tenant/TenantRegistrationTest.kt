package com.jiku.tenant

import com.jiku.TestcontainersConfiguration
import com.jiku.tenant.internal.OrganizerMembershipRepository
import com.jiku.tenant.internal.OrganizerRole
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

/**
 * Covers the JIKU-10 Definition of Done under the JIKU-48 membership model: a
 * registration with an organization name creates the user, the tenant, and the
 * OWNER membership atomically, and a failure partway through rolls back all of it.
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

    @Autowired
    lateinit var memberships: OrganizerMembershipRepository

    @BeforeEach
    @AfterEach
    fun reset() {
        memberships.deleteAll()
        users.deleteAll()
        tenants.deleteAll()
    }

    @Test
    fun `registration atomically creates the user and their owned organization`() {
        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Acme Events","email":"owner@acme.test","password":"supersecret"}"""),
            ).andExpect(status().isCreated())

        assertEquals(1, tenants.count(), "exactly one tenant should exist")
        assertEquals(1, users.count(), "exactly one user should exist")
        assertEquals(1, memberships.count(), "exactly one membership should exist")
        val tenant = tenants.findAll().first()
        val user = users.findAll().first()
        val membership = memberships.findAll().first()
        assertEquals(user.id, membership.userId, "the membership must belong to the new user")
        assertEquals(tenant.id.toString(), membership.tenantId, "the membership must point at the new tenant")
        assertEquals(OrganizerRole.OWNER, membership.role, "the founding user owns the organization")
        assertEquals("owner@acme.test", tenant.contactEmail)
    }

    @Test
    fun `a failure creating the user rolls back the tenant`() {
        // A pre-existing account with the same email forces the user insert to fail,
        // which must roll back everything written in the same transaction.
        users.saveAndFlush(OrganizerUser(email = "taken@acme.test", passwordHash = "irrelevant"))

        mockMvc
            .perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Acme Events","email":"taken@acme.test","password":"supersecret"}"""),
            ).andExpect(status().isConflict())

        assertEquals(0, tenants.count(), "no tenant may survive the rolled-back registration")
        assertEquals(0, memberships.count(), "no membership may survive the rolled-back registration")
        assertEquals(1, users.count(), "only the pre-existing account should remain")
    }
}
