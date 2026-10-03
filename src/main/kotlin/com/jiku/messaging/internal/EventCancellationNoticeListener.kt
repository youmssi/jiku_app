package com.jiku.messaging.internal

import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.TenantContext
import com.jiku.shared.async.Executors
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Delivers one guest's cancellation notice (render, send, retry, audit) in
 * reaction to an [EventCancellationNotice]. Unlike invitations there is no
 * lifecycle to report back — the audit log is the record. The notice carries the
 * tenant, which is bound for the tenant-scoped audit writes.
 */
@Component
class EventCancellationNoticeListener(
    private val notificationService: NotificationService,
) {
    @Async(Executors.BULK)
    @TransactionalEventListener(fallbackExecution = true)
    fun onEventCancellationNotice(notice: EventCancellationNotice) {
        TenantContext.withTenant(notice.tenantId) {
            notificationService.deliverCancellation(notice)
        }
    }
}
