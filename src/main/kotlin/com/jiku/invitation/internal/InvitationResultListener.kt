package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.InvitationDeliveryResult
import com.jiku.shared.TenantTransaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Updates an invitation's status from the notification module's delivery result,
 * closing the event-driven loop. The result carries the tenant, bound here for the
 * tenant-scoped update.
 *
 * A delivery that failed for good hands the guest over, once, to the event's other
 * channel when the event allows it (JIKU-273): the new invitation is QUEUED, so the
 * queue sweep sends it within its next run.
 */
@Component
class InvitationResultListener(
    private val tenantTransaction: TenantTransaction,
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
) {
    @EventListener
    fun onDeliveryResult(result: InvitationDeliveryResult) {
        tenantTransaction.run(result.tenantId) {
            val invitation = invitations.findById(result.invitationId).orElse(null) ?: return@run
            invitation.attempts = result.attempts
            when {
                result.delivered -> {
                    invitation.status = InvitationStatus.SENT
                    invitation.sentAt = Instant.now()
                    invitation.lastError = null
                }
                result.queued -> {
                    invitation.status = InvitationStatus.QUEUED
                    invitation.lastError = null
                }
                else -> {
                    invitation.status = InvitationStatus.FAILED
                    invitation.lastError = result.error
                }
            }
            invitations.save(invitation)
            if (invitation.status == InvitationStatus.FAILED) {
                fallBack(invitation)
            }
        }
    }

    private fun fallBack(failed: Invitation) {
        val guest = guests.findById(failed.guestId).orElse(null) ?: return
        if (guest.preferredChannel != null || guest.excludedFromInvitations) return
        val event = events.findEvent(failed.eventId) ?: return
        if (!event.channelFallback) return
        val other = event.channelOrder().firstOrNull { it != failed.channel && guest.canBeReachedOn(it) } ?: return
        if (invitations.findByGuestIdAndChannel(failed.guestId, other) != null) return
        invitations.save(Invitation(failed.eventId, failed.guestId, other).apply { status = InvitationStatus.QUEUED })
    }
}
