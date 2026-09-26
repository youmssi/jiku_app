package com.jiku.messaging.internal

import com.jiku.shared.TenantTransaction
import com.jiku.shared.TicketConfirmedNotice
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Sends a confirmed guest their ticket (JIKU-129) in reaction to a
 * [TicketConfirmedNotice]. The notice carries the tenant, bound for the
 * tenant-scoped audit writes, as for invitations and cancellations.
 */
@Component
class TicketConfirmationListener(
    private val tenantTransaction: TenantTransaction,
    private val notificationService: NotificationService,
) {
    @EventListener
    fun onTicketConfirmed(notice: TicketConfirmedNotice) {
        tenantTransaction.run(notice.tenantId) {
            notificationService.deliverTicketConfirmation(notice)
        }
    }
}
