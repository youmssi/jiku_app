package com.jiku.messaging

import com.jiku.messaging.internal.MessageCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * JIKU-188: dates in WhatsApp messages read at a glance, "sam. 14 nov. · 19 h",
 * in the event's zone, with the year only when it is not obvious.
 */
class ShortDateTest {
    private val catalog = MessageCatalog { "fr" }
    private val conakry = ZoneId.of("Africa/Conakry")
    private val now = Instant.parse("2026-09-27T10:00:00Z")

    private fun short(
        language: String,
        instant: String,
        zone: ZoneId = conakry,
        weekday: Boolean = true,
    ) = catalog.shortWhen(language, Instant.parse(instant), zone, weekday, now)

    @Test
    fun `a day this year is short, a round hour has no minutes`() {
        assertEquals("sam. 14 nov. · 19 h", short("fr", "2026-11-14T19:00:00Z"))
        assertEquals("Sat, Nov 14 · 7 PM", short("en", "2026-11-14T19:00:00Z"))
    }

    @Test
    fun `minutes show when they are not zero`() {
        assertEquals("sam. 14 nov. · 19 h 30", short("fr", "2026-11-14T19:30:00Z"))
        assertEquals("Sat, Nov 14 · 7:30 PM", short("en", "2026-11-14T19:30:00Z"))
    }

    @Test
    fun `a day far ahead or in a past year shows its year`() {
        assertEquals("sam. 2 oct. 2027 · 19 h", short("fr", "2027-10-02T19:00:00Z"))
        assertEquals("Sat, Dec 20, 2025 · 7 PM", short("en", "2025-12-20T19:00:00Z"))
    }

    @Test
    fun `a deadline can go without its weekday`() {
        assertEquals("7 nov. · 20 h", short("fr", "2026-11-07T20:00:00Z", weekday = false))
        assertEquals("Nov 7 · 8 PM", short("en", "2026-11-07T20:00:00Z", weekday = false))
    }

    @Test
    fun `the event's zone decides the day and the hour`() {
        assertEquals("dim. 15 nov. · 0 h 30", short("fr", "2026-11-14T23:30:00Z", ZoneId.of("Africa/Lagos")))
    }
}
