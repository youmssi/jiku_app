package com.jiku.messaging.internal

import com.jiku.shared.TenantContext
import com.jiku.shared.TicketConfirmedNotice
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Sends a confirmed guest their ticket (JIKU-129) in reaction to a
 * [TicketConfirmedNotice]. The notice carries the tenant, bound for the
 * tenant-scoped audit writes, as for invitations and cancellations.
 */
@Component
class TicketConfirmationListener(
    private val notificationService: NotificationService,
) {
    @EventListener
    @Transactional
    fun onTicketConfirmed(notice: TicketConfirmedNotice) {
        val previousTenant = TenantContext.get()
        TenantContext.set(notice.tenantId)
        try {
            notificationService.deliverTicketConfirmation(notice)
        } finally {
            if (previousTenant != null) TenantContext.set(previousTenant) else TenantContext.clear()
        }
    }
}
