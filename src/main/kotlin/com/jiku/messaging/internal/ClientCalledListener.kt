package com.jiku.messaging.internal

import com.jiku.shared.ClientCalled
import com.jiku.shared.TenantContext
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Tells a client it is their turn (JIKU-114). Runs once the call is committed —
 * a call rolled back sends nothing — and off the operator's request, so a slow
 * provider never delays the next call at the counter.
 */
@Component
class ClientCalledListener(
    private val notificationService: NotificationService,
) {
    @Async("invitationExecutor")
    @TransactionalEventListener
    fun on(called: ClientCalled) {
        TenantContext.withTenant(called.tenantId) { notificationService.deliverClientCalled(called) }
    }
}
