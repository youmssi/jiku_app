package com.jiku.checkin.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Organizer-facing trend data for one event — the detailed companion to
 * [DashboardController]'s live snapshot, for the analytics page.
 */
@RestController
@RequestMapping("/events/{eventId}/analytics")
@PreAuthorize("hasRole('ORGANIZER')")
class AnalyticsController(
    private val analyticsService: AnalyticsService,
) {
    @GetMapping
    fun get(
        @PathVariable eventId: UUID,
    ): AnalyticsResponse = analyticsService.analytics(eventId)
}
