package com.jiku.messaging.internal

import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.InvitationDeliveryResult
import com.jiku.shared.TenantContext
import com.jiku.shared.async.Executors
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * The notification module's inbound boundary: it reacts to a [GuestInvitedEvent]
 * by delivering the invitation (render, send, retry, audit) and then publishes an
 * [InvitationDeliveryResult] so the invitation module can update its status. The
 * event carries the tenant, which is bound for the tenant-scoped audit writes.
 *
 * It runs once the invitation's transaction has committed, on the bulk
 * executor and outside any transaction (JIKU-215): no database connection is
 * held while the provider answers, which can take seconds.
 */
@Component
class InvitationNotificationListener(
    private val notificationService: NotificationService,
    private val events: ApplicationEventPublisher,
) {
    @Async(Executors.BULK)
    @TransactionalEventListener(fallbackExecution = true)
    fun onGuestInvited(event: GuestInvitedEvent) {
        TenantContext.withTenant(event.tenantId) {
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
