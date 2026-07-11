package com.jiku.checkin.internal

import com.jiku.event.EventModuleApi
import com.jiku.invitation.InvitationModuleApi
import com.jiku.ticketing.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Aggregates the trend data behind an event's analytics page: check-ins bucketed by
 * hour in the event's own timezone, invitation delivery by channel/status, and guest
 * list growth leading up to the event. Reuses the same module-API boundaries as
 * [DashboardService] — no new cross-module coupling, just different aggregation.
 */
@Service
class AnalyticsService(
    private val events: EventModuleApi,
    private val invitation: InvitationModuleApi,
    private val ticketing: TicketingModuleApi,
) {
    @Transactional(readOnly = true)
    fun analytics(eventId: UUID): AnalyticsResponse {
        val event = events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        val zone = runCatching { ZoneId.of(event.timezone) }.getOrDefault(ZoneOffset.UTC)

        val checkInTimeline =
            ticketing
                .findTicketsByEvent(eventId)
                .mapNotNull { it.checkedInAt }
                .groupingBy { it.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS) }
                .eachCount()
                .toSortedMap()
                .map { (hour, count) -> TimeBucket(hour.format(HOUR_LABEL), count.toLong()) }

        val guestGrowth =
            invitation
                .listGuests(eventId)
                .groupingBy { it.createdAt.atZone(ZoneOffset.UTC).toLocalDate() }
                .eachCount()
                .toSortedMap()
                .map { (date, count) -> DateCount(date.format(DATE_LABEL), count.toLong()) }

        return AnalyticsResponse(
            checkInTimeline = checkInTimeline,
            channelBreakdown = invitation.channelBreakdown(eventId),
            guestGrowth = guestGrowth,
        )
    }

    private companion object {
        val HOUR_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:00")
        val DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
