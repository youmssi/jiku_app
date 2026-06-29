package com.jiku.checkin

import com.jiku.TestcontainersConfiguration
import com.jiku.shared.TenantContext
import com.jiku.ticketing.CheckInOutcome
import com.jiku.ticketing.TicketingModuleApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CheckInConcurrencyTest {
    @Autowired
    lateinit var ticketing: TicketingModuleApi

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `two near-simultaneous check-ins on the same ticket, exactly one succeeds`() {
        TenantContext.set("tenant-checkin")
        val code = ticketing.issueTicket(UUID.randomUUID(), UUID.randomUUID()).ticketCode

        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes =
                (1..2)
                    .map {
                        executor.submit<CheckInOutcome> {
                            TenantContext.set("tenant-checkin")
                            try {
                                ticketing.checkInByCode(code, "Gate $it").outcome
                            } finally {
                                TenantContext.clear()
                            }
                        }
                    }.map { it.get() }

            assertEquals(
                1,
                outcomes.count { it == CheckInOutcome.CHECKED_IN },
                "exactly one of two concurrent check-ins should succeed",
            )
            assertEquals(
                1,
                outcomes.count { it == CheckInOutcome.ALREADY_CHECKED_IN },
                "the losing check-in should report ALREADY_CHECKED_IN",
            )
        } finally {
            executor.shutdown()
        }
    }
}
