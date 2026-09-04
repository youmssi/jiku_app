package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.ConfirmationMode
import com.jiku.catalog.internal.PaymentMode
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
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Configuration par service (JIKU-86) : un service sans configuration est
 * utilisable (les défauts §5 s'appliquent), chaque option est persistée par
 * service, et le moteur lit les valeurs effectives.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ServiceConfigTest {
    @Autowired
    lateinit var configService: ServiceConfigService

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

    private fun serviceWithOneCabine(tenant: String): UUID {
        TenantContext.set(tenant)
        val resource = resources.save(Resource(name = "Cabine", type = ResourceType.LOCATION, timezone = "Africa/Conakry"))
        availabilities.save(
            ResourceAvailability(
                resourceId = requireNotNull(resource.id),
                dayOfWeek = 1,
                start = LocalTime.of(9, 0),
                end = LocalTime.of(13, 0),
            ),
        )
        val service = services.save(Service(name = "Coupe", timezone = "Africa/Conakry"))
        val serviceId = requireNotNull(service.id)
        requirements.save(ServiceRequirement(serviceId = serviceId, type = ResourceType.LOCATION, quantity = 1))
        return serviceId
    }

    @Test
    fun `a service with no configuration uses the defaults`() {
        TenantContext.set("cfg-tenant-defaults")
        val serviceId = serviceWithOneCabine("cfg-tenant-defaults")

        val effective = configService.effective(serviceId)
        assertEquals(ConfirmationMode.ON_REQUEST, effective.confirmationMode)
        assertEquals(30, effective.stepMinutes)
        assertEquals(30, effective.durationMinutes)
        assertEquals(0, effective.bufferMinutes)
        assertEquals(120, effective.minHorizonMinutes)
        assertEquals(30, effective.maxHorizonDays)
        assertEquals(1440L, effective.holdMinutes)
        assertEquals(24, effective.cancelDeadlineHours)
        assertEquals(10, effective.noShowToleranceMinutes)
        assertTrue(effective.walkInsAllowed)
        assertEquals(PaymentMode.FREE, effective.paymentMode)

        // Usable straight away: the 09:00-12:30 half-hour grid opens.
        assertEquals(8, engine.openSlots(serviceId, monday).size)
    }

    @Test
    fun `overrides persist per service and are read by the engine`() {
        val tenant = "cfg-tenant-overrides"
        val serviceId = serviceWithOneCabine(tenant)

        // Pas à 60 minutes + réservation « sur demande » courte (10 min).
        configService.update(
            serviceId,
            ServiceConfigUpdate(stepMinutes = 60, holdMinutes = 10, walkInsAllowed = false),
        )
        val effective = configService.effective(serviceId)
        assertEquals(60, effective.stepMinutes)
        assertEquals(10L, effective.holdMinutes)
        assertFalse(effective.walkInsAllowed)
        // Les autres options restent sur leur défaut.
        assertEquals(30, effective.durationMinutes)

        // Le moteur lit le pas effectif : la grille passe de 30 à 60 minutes.
        val opens = engine.openSlots(serviceId, monday)
        assertEquals(4, opens.size)
        val starts = opens.map { it.startsAt }
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), starts.first())
        assertEquals(3600L, starts[1].epochSecond - starts[0].epochSecond)
    }

    @Test
    fun `partial updates only touch the provided options`() {
        TenantContext.set("cfg-tenant-partial")
        val serviceId = serviceWithOneCabine("cfg-tenant-partial")

        configService.update(serviceId, ServiceConfigUpdate(cancelDeadlineHours = 2))
        val effective = configService.effective(serviceId)
        assertEquals(2, effective.cancelDeadlineHours)
        assertEquals(30, effective.stepMinutes)
        assertEquals(1440L, effective.holdMinutes)
        assertEquals(ConfirmationMode.ON_REQUEST, effective.confirmationMode)
    }
}
