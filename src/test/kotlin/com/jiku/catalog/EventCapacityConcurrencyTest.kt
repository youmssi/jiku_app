package com.jiku.catalog

import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.internal.Event
import com.jiku.catalog.internal.EventRepository
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.Executors
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class EventCapacityConcurrencyTest {
    @Autowired
    lateinit var events: EventModuleApi

    @Autowired
    lateinit var repository: EventRepository

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `concurrent reservations never exceed a one-seat event`() {
        TenantContext.set("tenant-capacity")
        val event = Event(name = "Concert", timezone = "UTC").apply { maxCapacity = 1 }
        val eventId = requireNotNull(repository.saveAndFlush(event).id)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes =
                (1..2)
                    .map {
                        executor.submit<Boolean> {
                            TenantContext.set("tenant-capacity")
                            try {
                                events.reserveAttendanceSlot(eventId)
                            } finally {
                                TenantContext.clear()
                            }
                        }
                    }.map { it.get() }
            assertEquals(1, outcomes.count { it }, "exactly one reservation should succeed for a one-seat event")
        } finally {
            executor.shutdown()
        }
    }
}
