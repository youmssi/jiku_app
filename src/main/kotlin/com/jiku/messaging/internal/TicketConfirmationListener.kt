package com.jiku.messaging.internal

import com.jiku.shared.TenantContext
import com.jiku.shared.TicketConfirmedNotice
import com.jiku.shared.async.Executors
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Sends a confirmed guest their ticket (JIKU-129) in reaction to a
 * [TicketConfirmedNotice]. The notice carries the tenant, bound for the
 * tenant-scoped audit writes, as for invitations and cancellations.
 */
@Component
class TicketConfirmationListener(
    private val notificationService: NotificationService,
) {
    @Async(Executors.BULK)
    @TransactionalEventListener(fallbackExecution = true)
    fun onTicketConfirmed(notice: TicketConfirmedNotice) {
        TenantContext.withTenant(notice.tenantId) {
            notificationService.deliverTicketConfirmation(notice)
        }
    }
}
