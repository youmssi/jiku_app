package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.Service
import com.jiku.catalog.internal.ServiceRepository
import com.jiku.catalog.internal.ServiceRequirement
import com.jiku.catalog.internal.ServiceRequirementRepository
import com.jiku.catalog.internal.ServiceReservationStatus
import com.jiku.catalog.internal.SlotEngine
import com.jiku.catalog.internal.SlotUnavailableException
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moteur de créneaux et réservation atomique (JIKU-85). Fuseau des tests :
 * Africa/Conakry, sans heure d'été, donc une heure locale égale son instant UTC.
 * Le lundi 2026-11-02 sert de journée de référence.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SlotEngineTest {
    @Autowired
    lateinit var engine: SlotEngine

    @Autowired
    lateinit var services: ServiceRepository

    @Autowired
    lateinit var requirements: ServiceRequirementRepository

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @AfterEach
    fun clearContext() = TenantContext.clear()

    private val monday = LocalDate.of(2026, 11, 2)

    private fun addResource(
        name: String,
        type: ResourceType,
        tenant: String,
    ): UUID {
        TenantContext.set(tenant)
        val resource = resources.save(Resource(name = name, type = type, timezone = "Africa/Conakry"))
        val id = requireNotNull(resource.id)
        availabilities.save(
            com.jiku.catalog.internal.ResourceAvailability(
                resourceId = id,
                dayOfWeek = 1,
                start = LocalTime.of(9, 0),
                end = LocalTime.of(13, 0),
            ),
        )
        return id
    }

    private fun addService(
        name: String,
        tenant: String,
        requirements: List<Pair<ResourceType, Int>>,
    ): UUID {
        TenantContext.set(tenant)
        val service = services.save(Service(name = name, timezone = "Africa/Conakry"))
        val serviceId = requireNotNull(service.id)
        for ((type, quantity) in requirements) {
            this.requirements.save(ServiceRequirement(serviceId = serviceId, type = type, quantity = quantity))
        }
        return serviceId
    }

    @Test
    fun `a slot is open only while a free resource of the required type exists`() {
        val tenant = "slot-tenant-open"
        addResource("Cabine A", ResourceType.LOCATION, tenant)
        val serviceId = addService("Coupe", tenant, listOf(ResourceType.LOCATION to 1))

        val opens = engine.openSlots(serviceId, monday)
        // 09:00 à 12:30 par pas de 30 minutes (8 créneaux), la plage s'arrêtant à 13:00.
        assertEquals(8, opens.size)

        // Réservation confirmée : la case de 09:00 disparaît des créneaux ouverts.
        engine.reserveConfirmed(serviceId, Instant.parse("2026-11-02T09:00:00Z"))
        val after = engine.openSlots(serviceId, monday)
        assertTrue(after.none { it.startsAt == Instant.parse("2026-11-02T09:00:00Z") })
        assertTrue(after.any { it.startsAt == Instant.parse("2026-11-02T09:30:00Z") })
    }

    @Test
    fun `two requirements are both needed for a slot to be open`() {
        val tenant = "slot-tenant-two-req"
        addResource("Coiffeuse", ResourceType.PERSON, tenant)
        addResource("Cabine", ResourceType.LOCATION, tenant)
        val serviceId =
            addService(
                "Coupe + cabine",
                tenant,
                listOf(ResourceType.PERSON to 1, ResourceType.LOCATION to 1),
            )

        assertEquals(8, engine.openSlots(serviceId, monday).size)
        val outcome = engine.reserveConfirmed(serviceId, Instant.parse("2026-11-02T09:00:00Z"))
        assertEquals(2, outcome.resourceIds.size)

        // Personne disponible ce jour-là → plus aucun créneau, même si le lieu est libre.
        TenantContext.set(tenant)
        val person = resources.findByActiveTrueAndTypeOrderByNameAsc(ResourceType.PERSON).single()
        resources.save(person.apply { active = false })

        assertTrue(engine.openSlots(serviceId, monday).isEmpty())
        assertThrows<SlotUnavailableException> {
            engine.reserveConfirmed(serviceId, Instant.parse("2026-11-02T10:00:00Z"))
        }
    }

    @Test
    fun `parallel clients on one slot lead to exactly one reservation`() {
        val tenant = "slot-tenant-parallel"
        addResource("Cabine Unique", ResourceType.LOCATION, tenant)
        val serviceId = addService("Solo", tenant, listOf(ResourceType.LOCATION to 1))
        val slot = Instant.parse("2026-11-02T11:00:00Z")

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    pool.submit(
                        Callable {
                            runCatching {
                                TenantContext.set(tenant)
                                try {
                                    engine.reserveConfirmed(serviceId, slot)
                                } finally {
                                    TenantContext.clear()
                                }
                            }.isSuccess
                        },
                    )
                }
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
            val successes = futures.count { it.get() }
            assertEquals(1, successes)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an expired pending hold releases the slot`() {
        val tenant = "slot-tenant-hold"
        addResource("Cabine Hold", ResourceType.LOCATION, tenant)
        val serviceId = addService("Sur demande", tenant, listOf(ResourceType.LOCATION to 1))
        val slot = Instant.parse("2026-11-02T12:00:00Z")

        // Sur demande : la case est bloquée en attente, puis un second appel échoue.
        val held = engine.reserve(serviceId, slot)
        assertEquals(ServiceReservationStatus.PENDING, held.status)
        assertThrows<SlotUnavailableException> {
            engine.reserve(serviceId, slot)
        }

        // La purge libère la demande expirée : la même case redevient réservable.
        engine.releaseExpiredHolds(Instant.now().plusSeconds(48 * 3600))
        assertEquals(ServiceReservationStatus.CONFIRMED, engine.reserveConfirmed(serviceId, slot).status)
    }
}
