package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Création et gestion des services et de leurs exigences (JIKU-87), tenant-scopées.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ServiceAdminTest {
    @Autowired
    lateinit var services: ServiceAdminService

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `create, list, rename and require resources for a service`() {
        TenantContext.set("svc-tenant-admin")
        val created = services.create("Coupe femme", "Africa/Conakry")
        assertEquals("Coupe femme", created.name)
        assertTrue(services.list().any { it.id == created.id })

        assertEquals("Coupe homme", services.updateName(created.id, "Coupe homme").name)

        val requirement = services.addRequirement(created.id, ResourceType.PERSON, 1)
        assertEquals(1, services.listRequirements(created.id).size)

        // Doublon du même type refusé.
        val conflict =
            assertThrows(ResponseStatusException::class.java) {
                services.addRequirement(created.id, ResourceType.PERSON, 2)
            }
        assertEquals(HttpStatus.CONFLICT.value(), conflict.statusCode.value())

        services.removeRequirement(created.id, requirement.id)
        assertTrue(services.listRequirements(created.id).isEmpty())
    }

    @Test
    fun `an invalid timezone is refused`() {
        TenantContext.set("svc-tenant-tz")
        val ex =
            assertThrows(ResponseStatusException::class.java) {
                services.create("Mauvaise zone", "Not/AZone")
            }
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.statusCode.value())
    }

    @Test
    fun `another tenant never sees or edits a service`() {
        TenantContext.set("svc-tenant-a")
        val service = services.create("Cabine A", "Africa/Conakry")

        TenantContext.clear()
        TenantContext.set("svc-tenant-b")

        assertTrue(services.list().isEmpty())
        val notFound =
            assertThrows(ResponseStatusException::class.java) {
                services.get(service.id)
            }
        assertEquals(HttpStatus.NOT_FOUND.value(), notFound.statusCode.value())
        assertThrows(ResponseStatusException::class.java) {
            services.addRequirement(service.id, ResourceType.LOCATION, 1)
        }
    }
}
