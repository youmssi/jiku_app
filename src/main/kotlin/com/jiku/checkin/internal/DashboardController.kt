package com.jiku.checkin.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Organizer-facing live metrics for one of their events. Read-only and cheap, so
 * the frontend can poll it for a near-real-time dashboard. Tenant is bound from
 * the JWT; the event is scoped by the path.
 */
@RestController
@RequestMapping("/events/{eventId}/dashboard")
@PreAuthorize("hasRole('ORGANIZER_ADMIN')")
class DashboardController(
    private val dashboardService: DashboardService,
) {
    @GetMapping
    fun get(
        @PathVariable eventId: UUID,
    ): DashboardResponse = dashboardService.dashboard(eventId)
}
