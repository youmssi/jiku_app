package com.jiku.checkin.internal

import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Organizer-facing check-in endpoints for one of their events. The validator
 * scanning UI (JIKU-24) drives the same use case through validator-link auth
 * (JIKU-23); this controller lets an authenticated organizer scan, search and
 * monitor attendance directly. Tenant is bound from the JWT; the event is scoped
 * by the path.
 */
@RestController
@RequestMapping("/events/{eventId}/checkin")
@PreAuthorize("hasRole('ORGANIZER')")
class CheckInController(
    private val checkInService: CheckInService,
) {
    @PostMapping("/scan")
    fun scan(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: ScanRequest,
    ): CheckInResponse = checkInService.checkInByCode(eventId, request.ticketCode, ORGANIZER_LABEL)

    @PostMapping("/manual")
    fun manual(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: ManualCheckInRequest,
    ): CheckInResponse = checkInService.checkInByGuest(eventId, request.guestId, ORGANIZER_LABEL)

    @GetMapping("/search")
    fun search(
        @PathVariable eventId: UUID,
        @RequestParam("q") query: String,
    ): List<GuestMatch> = checkInService.search(eventId, query)

    @GetMapping("/stats")
    fun stats(
        @PathVariable eventId: UUID,
    ): AttendanceResponse = checkInService.stats(eventId)

    private companion object {
        const val ORGANIZER_LABEL = "Organizer"
    }
}
