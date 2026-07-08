package com.jiku.invitation.internal

import com.jiku.event.InvitationChannel
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/events/{eventId}/invitations")
@PreAuthorize("hasRole('ORGANIZER_ADMIN')")
class InvitationController(
    private val sendingService: InvitationSendingService,
    private val dispatcher: InvitationDispatcher,
) {
    @PostMapping("/send")
    fun send(
        @PathVariable eventId: UUID,
        @RequestParam(name = "channels", defaultValue = "EMAIL") channels: Set<InvitationChannel>,
        @RequestParam(name = "onlyUnsent", defaultValue = "true") onlyUnsent: Boolean,
    ): SendInvitationsResult {
        val result = sendingService.queue(eventId, channels, onlyUnsent)
        dispatcher.dispatchPending(eventId)
        return result
    }

    @GetMapping
    fun statuses(
        @PathVariable eventId: UUID,
    ): List<InvitationStatusResponse> = sendingService.statuses(eventId)
}
