package com.jiku.messaging.internal

import com.jiku.shared.PhoneCodeRequested
import com.jiku.shared.TenantContext
import com.jiku.shared.async.Executors
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/** Sends a phone verification code once the request that created it has committed. */
@Component
class PhoneCodeListener(
    private val notificationService: NotificationService,
) {
    @Async(Executors.URGENT)
    @TransactionalEventListener
    fun on(requested: PhoneCodeRequested) {
        TenantContext.withTenant(requested.tenantId) { notificationService.deliverPhoneCode(requested) }
    }
}
