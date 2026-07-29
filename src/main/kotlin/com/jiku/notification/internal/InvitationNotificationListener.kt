package com.jiku.notification.internal

import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.InvitationDeliveryResult
import com.jiku.shared.TenantContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * The notification module's inbound boundary: it reacts to a [GuestInvitedEvent]
 * by delivering the invitation (render, send, retry, audit) and then publishes an
 * [InvitationDeliveryResult] so the invitation module can update its status. The
 * event carries the tenant, which is bound for the tenant-scoped audit writes.
 */
@Component
class InvitationNotificationListener(
    private val notificationService: NotificationService,
    private val events: ApplicationEventPublisher,
) {
    @EventListener
    @Transactional
    fun onGuestInvited(event: GuestInvitedEvent) {
        val previousTenant = TenantContext.get()
        TenantContext.set(event.tenantId)
        try {
            val outcome = notificationService.deliverInvitation(event)
            events.publishEvent(
                InvitationDeliveryResult(
                    invitationId = event.invitationId,
                    tenantId = event.tenantId,
                    delivered = outcome.delivered,
                    attempts = outcome.attempts,
                    error = outcome.error,
                    queued = outcome.queued,
                ),
            )
        } finally {
            if (previousTenant != null) TenantContext.set(previousTenant) else TenantContext.clear()
        }
    }
}
