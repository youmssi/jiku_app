package com.jiku.messaging.internal

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What a calendar entry needs to know about an event. */
data class CalendarEntry(
    /** Stable across resends, so a calendar updates the entry instead of adding a second one. */
    val uid: String,
    val title: String,
    val start: Instant,
    val end: Instant?,
    val location: String?,
    val description: String,
    val url: String,
)

/**
 * Calendar invites for a confirmed ticket: an iCalendar file (RFC 5545) that
 * Apple Calendar, Outlook and Google all read, and a Google Calendar link.
 * Times are written in UTC, so every calendar shows them in its own timezone.
 */
object EventCalendar {
    /** An event without an end is given this length, so it does not show as a zero-minute blip. */
    val DEFAULT_LENGTH: Duration = Duration.ofHours(2)

    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private const val GOOGLE_TEMPLATE = "https://calendar.google.com/calendar/render?action=TEMPLATE"
    private const val MAX_LINE_OCTETS = 75

    fun ics(
        entry: CalendarEntry,
        now: Instant = Instant.now(),
    ): ByteArray {
        val lines =
            listOfNotNull(
                "BEGIN:VCALENDAR",
                "VERSION:2.0",
                "PRODID:-//Jiku//Tickets//FR",
                "CALSCALE:GREGORIAN",
                "METHOD:PUBLISH",
                "BEGIN:VEVENT",
                "UID:${escape(entry.uid)}",
                "DTSTAMP:${UTC_STAMP.format(now)}",
                "DTSTART:${UTC_STAMP.format(entry.start)}",
                "DTEND:${UTC_STAMP.format(endOf(entry))}",
                "SUMMARY:${escape(entry.title)}",
                entry.location?.takeIf { it.isNotBlank() }?.let { "LOCATION:${escape(it)}" },
                "DESCRIPTION:${escape(entry.description)}",
                "URL:${entry.url}",
                "END:VEVENT",
                "END:VCALENDAR",
            )
        return lines.joinToString(separator = "\r\n", postfix = "\r\n") { fold(it) }.toByteArray(StandardCharsets.UTF_8)
    }

    fun googleLink(entry: CalendarEntry): String =
        buildString {
            append(GOOGLE_TEMPLATE)
            append("&text=").append(encode(entry.title))
            append("&dates=").append(UTC_STAMP.format(entry.start)).append('/').append(UTC_STAMP.format(endOf(entry)))
            append("&details=").append(encode(entry.description))
            entry.location?.takeIf { it.isNotBlank() }?.let { append("&location=").append(encode(it)) }
        }

    private fun endOf(entry: CalendarEntry): Instant = entry.end?.takeIf { it.isAfter(entry.start) } ?: entry.start.plus(DEFAULT_LENGTH)

    /** Text values escape backslash, semicolon, comma and newlines (RFC 5545 §3.3.11). */
    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")

    /** Lines longer than 75 octets continue on the next line after a space (RFC 5545 §3.1), never inside a character. */
    private fun fold(line: String): String {
        if (line.toByteArray(StandardCharsets.UTF_8).size <= MAX_LINE_OCTETS) return line
        val folded = StringBuilder()
        var octets = 0
        var limit = MAX_LINE_OCTETS
        var index = 0
        while (index < line.length) {
            val codePoint = line.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val size = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8).size
            if (octets + size > limit) {
                folded.append("\r\n ")
                octets = 0
                limit = MAX_LINE_OCTETS - 1
            }
            folded.appendCodePoint(codePoint)
            octets += size
            index += chars
        }
        return folded.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
