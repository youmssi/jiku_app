package com.jiku.messaging.internal

import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.TenantTransaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Delivers one guest's cancellation notice (render, send, retry, audit) in
 * reaction to an [EventCancellationNotice]. Unlike invitations there is no
 * lifecycle to report back — the audit log is the record. The notice carries the
 * tenant, which is bound for the tenant-scoped audit writes.
 */
@Component
class EventCancellationNoticeListener(
    private val tenantTransaction: TenantTransaction,
    private val notificationService: NotificationService,
) {
    @EventListener
    fun onEventCancellationNotice(notice: EventCancellationNotice) {
        tenantTransaction.run(notice.tenantId) {
            notificationService.deliverCancellation(notice)
        }
    }
}
