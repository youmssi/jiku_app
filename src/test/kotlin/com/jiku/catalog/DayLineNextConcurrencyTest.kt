package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.ConfirmationMode
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceConfigService
import com.jiku.catalog.internal.ServiceConfigUpdate
import com.jiku.catalog.internal.SlotEngine
import com.jiku.shared.TenantContext
import com.jiku.ticket.TicketingModuleApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La concurrence de la ligne du jour (JIKU-88) : deux postes qui appuient sur
 * « suivant » en même temps ne reçoivent jamais la même personne — la transition
 * EN_ATTENTE → APPELÉ est réclamée atomiquement, le perdant resélectionne.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DayLineNextConcurrencyTest {
    @Autowired
    lateinit var engine: SlotEngine

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var configService: ServiceConfigService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var ticketing: TicketingModuleApi

    @AfterEach
    fun clearContext() = TenantContext.clear()

    private val tenant = "dayline-next-race"
    private val dayStart = Instant.parse("2026-11-02T00:00:00Z")
    private val dayEnd = Instant.parse("2026-11-03T00:00:00Z")

    @Test
    fun `two simultaneous next calls never hand out the same person`() {
        TenantContext.set(tenant)
        val serviceId = serviceWithMorning()
        val slot = Instant.parse("2026-11-02T09:00:00Z")

        // Trois personnes en attente : trois postes appellent en même temps.
        (0..2).forEach { i ->
            engine.bookClient(serviceId, slot.plusSeconds(1800L * i), "Client$i", "+22460000001$i")
        }
        val codes = ticketing.serviceLine(serviceId, dayStart, dayEnd).map { it.ticketCode }
        codes.forEachIndexed { index, code ->
            val at = slot.plusSeconds(1800L * index).minusSeconds(300)
            assertEquals(
                com.jiku.ticket.LineOutcome.OK,
                ticketing.arriveByCode(serviceId, code, at, dayStart, dayEnd, rankDay = LocalDate.parse("2026-11-02")).outcome,
            )
        }

        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val gate = CountDownLatch(3)
        val called = CopyOnWriteArrayList<String>()
        val now = slot.plusSeconds(1920)
        repeat(3) {
            pool.execute {
                TenantContext.set(tenant)
                try {
                    gate.countDown()
                    start.await()
                    ticketing.callNext(serviceId, dayStart, dayEnd, now, 10)?.ticketCode?.let { called += it }
                } finally {
                    TenantContext.clear()
                }
            }
        }
        assertTrue(gate.await(10, TimeUnit.SECONDS))
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        // Chaque personne a été appelée exactement une fois, personne en double.
        assertEquals(3, called.size)
        assertEquals(called.size, called.groupBy { it }.keys.size)
        val statuses = ticketing.serviceLine(serviceId, dayStart, dayEnd).map { it.status }.toSet()
        assertEquals(setOf("CALLED"), statuses)
    }

    @Test
    fun `two simultaneous first arrivals get distinct sequential day ranks`() {
        TenantContext.set(tenant)
        val serviceId = serviceWithMorning()
        val slot = Instant.parse("2026-11-02T09:00:00Z")

        engine.bookClient(serviceId, slot, "Alpha", "+224600000040")
        engine.bookClient(serviceId, slot.plusSeconds(1800), "Beta", "+224600000041")
        val codes = ticketing.serviceLine(serviceId, dayStart, dayEnd).map { it.ticketCode }
        assertEquals(2, codes.size)

        // Deux premiers arrivants de la journée : même s'ils passent en même
        // temps, le compteur (verrou + création en propre transaction) leur
        // donne des rangs 1 et 2 — jamais deux fois le même numéro.
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val gate = CountDownLatch(2)
        val ranks = CopyOnWriteArrayList<Int>()
        repeat(2) {
            pool.execute {
                TenantContext.set(tenant)
                try {
                    gate.countDown()
                    start.await()
                    val at = slot.plusSeconds(1800L * it).minusSeconds(300)
                    val result =
                        ticketing.arriveByCode(
                            serviceId,
                            codes[it],
                            at,
                            dayStart,
                            dayEnd,
                            rankDay = LocalDate.parse("2026-11-02"),
                        )
                    result.ticket?.dayRank?.let { ranks += it }
                } finally {
                    TenantContext.clear()
                }
            }
        }
        assertTrue(gate.await(10, TimeUnit.SECONDS))
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(2, ranks.size)
        assertEquals(setOf(1, 2), ranks.toSet())
    }

    private fun serviceWithMorning(): UUID {
        val resource = resources.save(Resource(name = "Coiffeuse", type = ResourceType.PERSON, timezone = "Africa/Conakry"))
        availabilities.save(
            ResourceAvailability(
                resourceId = requireNotNull(resource.id),
                dayOfWeek = 1,
                start = LocalTime.of(9, 0),
                end = LocalTime.of(13, 0),
            ),
        )
        val service = services.create("Coupe", "Africa/Conakry")
        services.addRequirement(service.id, ResourceType.PERSON, 1)
        configService.update(
            service.id,
            ServiceConfigUpdate(confirmationMode = ConfirmationMode.INSTANTANEOUS, maxHorizonDays = 365),
        )
        return service.id
    }
}
