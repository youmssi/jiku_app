package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.Service
import com.jiku.catalog.internal.ServiceConfigService
import com.jiku.catalog.internal.ServiceConfigUpdate
import com.jiku.catalog.internal.ServiceRepository
import com.jiku.catalog.internal.ServiceRequirement
import com.jiku.catalog.internal.ServiceRequirementRepository
import com.jiku.catalog.internal.SlotEngine
import com.jiku.catalog.internal.SlotUnavailableException
import com.jiku.money.internal.Subscription
import com.jiku.money.internal.SubscriptionRepository
import com.jiku.shared.TenantContext
import com.jiku.support.TestDates.MONDAY
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sessions (JIKU-174): one resource serves several clients in the same
 * slot, up to the service's clients per slot, itself capped by the plan (Solo 1,
 * Teams 10, Organisation 30). Each client takes one place; a place is never sold
 * twice, even under concurrent bookings.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class GroupSessionTest {
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

    @Autowired
    lateinit var configService: ServiceConfigService

    @Autowired
    lateinit var subscriptions: SubscriptionRepository

    @AfterEach
    fun clearContext() = TenantContext.clear()

    private val nine: Instant = Instant.parse("${MONDAY}T09:00:00Z")

    @Test
    fun `a group session fills place by place, then closes`() {
        val tenant = tenantOnPlan("Teams")
        addRoom(tenant, "Studio")
        val yoga = addService(tenant, "Yoga", clientsPerSlot = 3)

        assertEquals(3, placesAt(yoga, nine))
        engine.bookClient(yoga, nine, "Awa", "+224600000001")
        engine.bookClient(yoga, nine, "Binta", "+224600000002")
        assertEquals(1, placesAt(yoga, nine))
        engine.bookClient(yoga, nine, "Coumba", "+224600000003")

        assertTrue(engine.openSlots(yoga, MONDAY).none { it.startsAt == nine })
        assertThrows<SlotUnavailableException> { engine.bookClient(yoga, nine, "Diaka", "+224600000004") }
    }

    @Test
    fun `a resource in one session offers no place to another service or another start`() {
        val tenant = tenantOnPlan("Teams")
        addRoom(tenant, "Studio")
        val yoga = addService(tenant, "Yoga", clientsPerSlot = 5)
        configService.update(yoga, ServiceConfigUpdate(durationMinutes = 60))
        val pilates = addService(tenant, "Pilates", clientsPerSlot = 5)

        engine.bookClient(yoga, nine, "Awa", "+224600000011")

        assertTrue(engine.openSlots(pilates, MONDAY).none { it.startsAt == nine })
        assertThrows<SlotUnavailableException> { engine.bookClient(pilates, nine, "Binta", "+224600000012") }
        // A 60-minute session at 09:00 still runs at 09:30.
        assertThrows<SlotUnavailableException> {
            engine.bookClient(yoga, nine.plusSeconds(1800), "Coumba", "+224600000013")
        }
    }

    @Test
    fun `the plan caps the clients per slot`() {
        val solo = tenantOnPlan("Solo")
        val service = addService(solo, "Coaching", clientsPerSlot = null)

        val refused =
            assertThrows<ResponseStatusException> {
                configService.update(service, ServiceConfigUpdate(clientsPerSlot = 2))
            }
        assertEquals(HttpStatus.CONFLICT, refused.statusCode)
        assertEquals(1, configService.effective(service).maxClientsPerSlot)

        val organisation = tenantOnPlan("Organisation")
        val big = addService(organisation, "Visite guidée", clientsPerSlot = 30)
        assertEquals(30, configService.effective(big).clientsPerSlot)
        assertThrows<ResponseStatusException> { configService.update(big, ServiceConfigUpdate(clientsPerSlot = 31)) }
    }

    @Test
    fun `a downgrade never leaves a session larger than the new plan`() {
        val tenant = tenantOnPlan("Teams")
        val service = addService(tenant, "Yoga", clientsPerSlot = 8)
        assertEquals(8, configService.effective(service).clientsPerSlot)

        TenantContext.set(tenant)
        subscriptions.save(subscriptions.findCurrent().single().apply { plan = "Solo" })

        assertEquals(1, configService.effective(service).clientsPerSlot)
    }

    @Test
    fun `concurrent bookings never sell more places than the session holds`() {
        val tenant = tenantOnPlan("Teams")
        addRoom(tenant, "Studio")
        val yoga = addService(tenant, "Yoga", clientsPerSlot = 3)

        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures =
                (1..8).map { index ->
                    pool.submit(
                        Callable {
                            runCatching {
                                TenantContext.set(tenant)
                                try {
                                    engine.bookClient(yoga, nine, "Client $index", "+22460000010$index")
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
            assertTrue(successes in 1..3, "between one and three bookings succeed, got $successes")

            TenantContext.set(tenant)
            val left = engine.openSlots(yoga, MONDAY).firstOrNull { it.startsAt == nine }?.placesLeft ?: 0
            assertEquals(3 - successes, left)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun placesAt(
        serviceId: UUID,
        startsAt: Instant,
    ): Int = engine.openSlots(serviceId, MONDAY).first { it.startsAt == startsAt }.placesLeft

    private fun tenantOnPlan(plan: String): String {
        val tenant = "group-${UUID.randomUUID()}"
        TenantContext.set(tenant)
        subscriptions.save(
            Subscription(
                plan = plan,
                resourceLimit = null,
                resourcesActive = 1,
                startedAt = Instant.now(),
                expiresAt = null,
            ),
        )
        return tenant
    }

    private fun addRoom(
        tenant: String,
        name: String,
    ): UUID {
        TenantContext.set(tenant)
        val room = resources.save(Resource(name = name, type = ResourceType.LOCATION, timezone = "Africa/Conakry"))
        val id = requireNotNull(room.id)
        availabilities.save(ResourceAvailability(resourceId = id, dayOfWeek = 1, start = LocalTime.of(9, 0), end = LocalTime.of(13, 0)))
        return id
    }

    private fun addService(
        tenant: String,
        name: String,
        clientsPerSlot: Int?,
    ): UUID {
        TenantContext.set(tenant)
        val service = services.save(Service(name = name, timezone = "Africa/Conakry"))
        val id = requireNotNull(service.id)
        configService.update(id, ServiceConfigUpdate(maxHorizonDays = 365, clientsPerSlot = clientsPerSlot))
        requirements.save(ServiceRequirement(serviceId = id, type = ResourceType.LOCATION, quantity = 1))
        return id
    }
}
