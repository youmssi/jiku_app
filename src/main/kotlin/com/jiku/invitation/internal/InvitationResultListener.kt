package com.jiku.invitation.internal

import com.jiku.shared.InvitationDeliveryResult
import com.jiku.shared.TenantContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Updates an invitation's status from the notification module's delivery result,
 * closing the event-driven loop. The result carries the tenant, bound here for the
 * tenant-scoped update.
 */
@Component
class InvitationResultListener(
    private val invitations: InvitationRepository,
) {
    @EventListener
    @Transactional
    fun onDeliveryResult(result: InvitationDeliveryResult) {
        val previousTenant = TenantContext.get()
        TenantContext.set(result.tenantId)
        try {
            val invitation = invitations.findById(result.invitationId).orElse(null) ?: return
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
        } finally {
            if (previousTenant != null) TenantContext.set(previousTenant) else TenantContext.clear()
        }
    }
}
