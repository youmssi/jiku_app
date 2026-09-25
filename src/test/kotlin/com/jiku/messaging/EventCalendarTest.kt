package com.jiku.messaging

import com.jiku.messaging.internal.CalendarEntry
import com.jiku.messaging.internal.EventCalendar
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventCalendarTest {
    private val entry =
        CalendarEntry(
            uid = "guest-1@jiku",
            title = "Gala, dîner; soirée",
            start = Instant.parse("2026-12-12T18:00:00Z"),
            end = null,
            location = "Palais du Peuple, Conakry",
            description = "Maison Aminata · https://jiku.app/invitation/abc/ticket",
            url = "https://jiku.app/invitation/abc/ticket",
        )

    @Test
    fun `the invite is a valid calendar with escaped text and UTC times`() {
        val ics = String(EventCalendar.ics(entry, now = Instant.parse("2026-09-25T10:00:00Z")))

        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertTrue(ics.contains("SUMMARY:Gala\\, dîner\\; soirée\r\n"))
        assertTrue(ics.contains("DTSTART:20261212T180000Z\r\n"))
        assertTrue(ics.contains("DTEND:20261212T200000Z\r\n"), "an event without an end lasts the default two hours")
        assertTrue(ics.contains("LOCATION:Palais du Peuple\\, Conakry\r\n"))
    }

    @Test
    fun `no line is longer than 75 octets`() {
        val long = entry.copy(description = "é".repeat(120))
        val ics = EventCalendar.ics(long)

        String(ics).split("\r\n").forEach { line ->
            assertTrue(line.toByteArray().size <= 75, "line too long: $line")
        }
        assertFalse(String(ics).contains("�"), "folding must never split a character")
    }

    @Test
    fun `the Google link carries the title, the times and the place`() {
        val link = EventCalendar.googleLink(entry)

        assertTrue(link.startsWith("https://calendar.google.com/calendar/render?action=TEMPLATE"))
        assertTrue(link.contains("&dates=20261212T180000Z/20261212T200000Z"))
        assertTrue(link.contains("&text=Gala%2C%20d%C3%AEner%3B%20soir%C3%A9e"))
        assertEquals(1, link.split("&location=").size - 1)
    }
}
