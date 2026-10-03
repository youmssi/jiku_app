package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.MessagingSpendAlertJob
import com.jiku.shared.OpsAlert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 107: yesterday's platform message costs are compared with their ceilings each morning. */
@SpringBootTest(
    properties = [
        "notification.spend-alert.whatsapp-daily-usd=20",
        "notification.spend-alert.sms-daily-count=3",
    ],
)
@Import(TestcontainersConfiguration::class)
@RecordApplicationEvents
class MessagingSpendAlertTest {
    @Autowired
    lateinit var job: MessagingSpendAlertJob

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var events: ApplicationEvents

    private fun whatsApp(
        at: Instant,
        usdMinor: Long,
        pool: String = "PLATFORM",
    ) {
        jdbc.update(
            "INSERT INTO whatsapp_message_cost (id, tenant_id, pool, category, cost_usd_minor, cost_gnf_minor, created_at) " +
                "VALUES (?, 'spend-tenant', ?, 'UTILITY', ?, 0, ?)",
            UUID.randomUUID(),
            pool,
            usdMinor,
            Timestamp.from(at),
        )
    }

    private fun sms(
        at: Instant,
        status: String = "SENT",
    ) {
        jdbc.update(
            "INSERT INTO notification_log (id, tenant_id, channel, recipient, status, attempt, created_at) " +
                "VALUES (?, 'spend-tenant', 'SMS', '+224620000000', ?, 1, ?)",
            UUID.randomUUID(),
            status,
            Timestamp.from(at),
        )
    }

    private fun alerts() = events.stream(OpsAlert::class.java).toList()

    @Test
    fun `alerts when the day's platform WhatsApp cost and SMS count exceed their ceilings`() {
        val day = LocalDate.parse("2026-08-10")
        val noon = Instant.parse("2026-08-10T12:00:00Z")
        whatsApp(noon, 1_500)
        whatsApp(noon, 1_000)
        whatsApp(noon, 9_999, pool = "TENANT")
        whatsApp(Instant.parse("2026-08-11T00:00:00Z"), 9_999)
        repeat(4) { sms(noon) }
        sms(noon, status = "FAILED")

        job.check(day)

        val alert = alerts().single()
        assertTrue(alert.message.contains("25.00 USD"), alert.message)
        assertTrue(alert.message.contains("SMS: 4 sent"), alert.message)
    }

    @Test
    fun `stays quiet when the day is under both ceilings`() {
        val noon = Instant.parse("2026-08-12T12:00:00Z")
        whatsApp(noon, 500)
        sms(noon)

        job.check(LocalDate.parse("2026-08-12"))

        assertEquals(0, alerts().size)
    }
}
