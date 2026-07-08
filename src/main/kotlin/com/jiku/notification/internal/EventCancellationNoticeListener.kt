package com.jiku.notification.internal

import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.TenantContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

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
    @EventListener
    @Transactional
    fun onEventCancellationNotice(notice: EventCancellationNotice) {
        val previousTenant = TenantContext.get()
        TenantContext.set(notice.tenantId)
        try {
            notificationService.deliverCancellation(notice)
        } finally {
            if (previousTenant != null) TenantContext.set(previousTenant) else TenantContext.clear()
        }
    }
}
