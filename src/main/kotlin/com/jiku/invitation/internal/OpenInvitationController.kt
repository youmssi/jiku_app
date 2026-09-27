package com.jiku.invitation.internal

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The organization's side of an event's open invitation (JIKU-184): its
 * settings and counts, and the people who answered.
 */
@RestController
@RequestMapping("/events/{eventId}/open-invitation")
@PreAuthorize("hasRole('ORGANIZER')")
class OpenInvitationController(
    private val service: OpenInvitationService,
) {
    /** 204 while the event has no open invitation yet. */
    @GetMapping
    fun settings(
        @PathVariable eventId: UUID,
    ): ResponseEntity<OpenInvitationView> = service.settings(eventId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    /** Opens the invitation the first time, then changes its settings. */
    @PutMapping
    fun update(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: OpenInvitationSettingsRequest,
    ): OpenInvitationView = service.update(eventId, request)

    @GetMapping("/responses")
    fun responses(
        @PathVariable eventId: UUID,
    ): List<OrganizerOpenResponseView> = service.responses(eventId)

    @DeleteMapping("/responses/{responseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @PathVariable eventId: UUID,
        @PathVariable responseId: UUID,
    ) = service.remove(eventId, responseId)
}

/**
 * The public side (JIKU-184), without an account: a shared card's page and the
 * answer, found by the invitation's code. The code binds the tenant it belongs
 * to before the transactional service runs.
 */
@RestController
@RequestMapping("/open/{code}")
class PublicOpenInvitationController(
    private val service: OpenInvitationService,
) {
    @GetMapping
    fun view(
        @PathVariable code: String,
    ): PublicOpenInvitationView = service.route(code) { eventId -> service.publicView(eventId) }

    @PostMapping("/responses")
    fun respond(
        @PathVariable code: String,
        @Valid @RequestBody request: OpenResponseRequest,
    ): OpenResponseView = service.route(code) { eventId -> service.respond(eventId, request, OpenResponseChannel.WEB) }
}
