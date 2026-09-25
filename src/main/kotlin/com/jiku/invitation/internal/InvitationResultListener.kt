package com.jiku.invitation.internal

import com.jiku.shared.InvitationDeliveryResult
import com.jiku.shared.TenantTransaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Updates an invitation's status from the notification module's delivery result,
 * closing the event-driven loop. The result carries the tenant, bound here for the
 * tenant-scoped update.
 */
@Component
class InvitationResultListener(
    private val tenantTransaction: TenantTransaction,
    private val invitations: InvitationRepository,
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
        }
    }
}
