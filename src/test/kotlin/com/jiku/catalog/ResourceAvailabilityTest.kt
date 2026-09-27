package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.shared.TenantContext
import com.jiku.support.TestDates.MONDAY
import com.jiku.support.TestDates.TUESDAY
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ressources et disponibilité (JIKU-84) : création/modification/désactivation,
 * la règle « libre sur cette case ? » (horaire, indisponibilité qui prime, créneau
 * multi-jour), et l'isolation entre tenants.
 *
 * Fuseau choisi : Africa/Conakry, sans heure d'été, donc une heure locale égale
 * son instant UTC — les instants des tests restent lisibles.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ResourceAvailabilityTest {
    @Autowired
    lateinit var resources: ResourceModuleApi

    @AfterEach
    fun clearContext() = TenantContext.clear()

    private val mondayMorning = Instant.parse("${MONDAY}T09:30:00Z") // lundi
    private val mondayTen = Instant.parse("${MONDAY}T10:00:00Z")

    @Test
    fun `create edit and disable a resource with a weekly schedule`() {
        TenantContext.set("tenant-resource-crud")
        val resource = resources.createResource("Cabine 1", ResourceType.LOCATION, "Africa/Conakry")
        assertTrue(resource.active)

        val availability = resources.addAvailability(resource.id, 1, LocalTime.of(9, 0), LocalTime.of(13, 0))
        assertEquals(1, resources.listAvailability(resource.id).size)
        assertTrue(resources.isSlotFree(resource.id, mondayMorning, mondayTen))

        // Modification du nom, puis désactivation : le créneau n'est plus libre.
        val renamed = resources.updateResource(resource.id, name = "Cabine VIP")
        assertEquals("Cabine VIP", renamed.name)
        resources.updateResource(resource.id, active = false)
        assertFalse(resources.isSlotFree(resource.id, mondayMorning, mondayTen))

        // Réactivation : le créneau redevient libre.
        resources.updateResource(resource.id, active = true)
        assertTrue(resources.isSlotFree(resource.id, mondayMorning, mondayTen))
        assertNotNull(availability.id)
    }

    @Test
    fun `an unavailability overrides the weekly schedule`() {
        TenantContext.set("tenant-resource-leave")
        val resource = resources.createResource("Cabine 2", ResourceType.LOCATION, "Africa/Conakry")
        resources.addAvailability(resource.id, 1, LocalTime.of(9, 0), LocalTime.of(13, 0))
        assertTrue(resources.isSlotFree(resource.id, mondayTen, mondayTen.plusSeconds(1800)))

        resources.addUnavailability(
            resource.id,
            Instant.parse("${MONDAY}T09:00:00Z"),
            Instant.parse("${MONDAY}T11:00:00Z"),
            reason = "Congé",
        )

        // Pendant le congé, le créneau n'est plus libre malgré l'horaire…
        assertFalse(resources.isSlotFree(resource.id, mondayTen, mondayTen.plusSeconds(1800)))
        // … mais il redevient libre après la fin du congé, toujours dans l'horaire.
        assertTrue(
            resources.isSlotFree(
                resource.id,
                Instant.parse("${MONDAY}T12:00:00Z"),
                Instant.parse("${MONDAY}T12:30:00Z"),
            ),
        )
    }

    @Test
    fun `a slot is not free outside the schedule or across days`() {
        TenantContext.set("tenant-resource-bounds")
        val resource = resources.createResource("Cabine 3", ResourceType.LOCATION, "Africa/Conakry")
        resources.addAvailability(resource.id, 1, LocalTime.of(9, 0), LocalTime.of(13, 0))

        // Hors de la plage hebdomadaire (l'après-midi du lundi).
        assertFalse(
            resources.isSlotFree(
                resource.id,
                Instant.parse("${MONDAY}T14:00:00Z"),
                Instant.parse("${MONDAY}T14:30:00Z"),
            ),
        )
        // Créneau qui enjambe la frontière du jour.
        assertFalse(
            resources.isSlotFree(
                resource.id,
                Instant.parse("${MONDAY}T12:00:00Z"),
                Instant.parse("${TUESDAY}T09:00:00Z"),
            ),
        )
        // Aucun horaire déclaré → jamais libre.
        val bare = resources.createResource("Cabine 4", ResourceType.LOCATION, "Africa/Conakry")
        assertFalse(resources.isSlotFree(bare.id, mondayMorning, mondayTen))
    }

    @Test
    fun `another tenant never sees or modifies a resource`() {
        TenantContext.set("tenant-resource-a")
        val resource = resources.createResource("Cabine A", ResourceType.LOCATION, "Africa/Conakry")
        resources.addAvailability(resource.id, 1, LocalTime.of(9, 0), LocalTime.of(13, 0))
        assertTrue(resources.isSlotFree(resource.id, mondayMorning, mondayTen))

        TenantContext.clear()
        TenantContext.set("tenant-resource-b")

        assertEquals(0, resources.listResources().size)
        assertNull(resources.findResource(resource.id))
        assertFalse(resources.isSlotFree(resource.id, mondayMorning, mondayTen))
        // Modifier la ressource d'un autre tenant est refusé comme inexistante.
        val ex =
            assertThrows<ResponseStatusException> {
                resources.addAvailability(resource.id, 2, LocalTime.of(8, 0), LocalTime.of(12, 0))
            }
        assertEquals(HttpStatus.NOT_FOUND.value(), ex.statusCode.value())
    }
}
