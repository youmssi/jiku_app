package com.jiku.messaging.internal

import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.InvitationDeliveryResult
import com.jiku.shared.TenantTransaction
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * The notification module's inbound boundary: it reacts to a [GuestInvitedEvent]
 * by delivering the invitation (render, send, retry, audit) and then publishes an
 * [InvitationDeliveryResult] so the invitation module can update its status. The
 * event carries the tenant, which is bound for the tenant-scoped audit writes.
 */
@Component
class InvitationNotificationListener(
    private val tenantTransaction: TenantTransaction,
    private val notificationService: NotificationService,
    private val events: ApplicationEventPublisher,
) {
    @EventListener
    fun onGuestInvited(event: GuestInvitedEvent) {
        tenantTransaction.run(event.tenantId) {
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
        }
    }
}
