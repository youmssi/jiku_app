package com.jiku.invitation.internal

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Sends pending invitations off the request thread. Runs on the tenant-aware async
 * executor, so [InvitationDispatchWorker] sees the right tenant. Each invitation is
 * processed in its own transaction so one failure does not roll back the others.
 */
@Component
class InvitationDispatcher(
    private val invitations: InvitationRepository,
    private val worker: InvitationDispatchWorker,
) {
    @Async("invitationExecutor")
    fun dispatchPending(eventId: UUID) {
        invitations.findByEventIdAndStatus(eventId, InvitationStatus.PENDING).forEach { invitation ->
            worker.process(requireNotNull(invitation.id))
        }
    }
}
