package com.jiku.invitation.internal

import com.jiku.catalog.InvitationChannel
import com.jiku.shared.SenderGate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/events/{eventId}/invitations")
@PreAuthorize("hasRole('ORGANIZER')")
class InvitationController(
    private val sendingService: InvitationSendingService,
    private val dispatcher: InvitationDispatcher,
    private val senders: SenderGate,
) {
    /** Sending is the first thing done on the organization's behalf: it needs a proven address (JIKU-253). */
    @PostMapping("/send")
    fun send(
        authentication: Authentication,
        @PathVariable eventId: UUID,
        // Absent, the event's channels apply, one per guest (JIKU-273); one channel only, like a reminder, forces it.
        @RequestParam(name = "channels", required = false) channels: Set<InvitationChannel>?,
        @RequestParam(name = "onlyUnsent", defaultValue = "true") onlyUnsent: Boolean,
        // A reminder reaches only the guests who have not answered yet (JIKU-273).
        @RequestParam(name = "onlyUnanswered", defaultValue = "false") onlyUnanswered: Boolean,
    ): SendInvitationsResult {
        senders.requireVerifiedSender(authentication.name)
        val result = sendingService.queue(eventId, channels, onlyUnsent, onlyUnanswered)
        dispatcher.dispatchPending(eventId)
        return result
    }

    @GetMapping
    fun statuses(
        @PathVariable eventId: UUID,
    ): List<InvitationStatusResponse> = sendingService.statuses(eventId)
}
