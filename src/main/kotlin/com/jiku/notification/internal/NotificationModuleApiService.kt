package com.jiku.notification.internal

import com.jiku.notification.DeliverabilityInfo
import com.jiku.notification.NotificationModuleApi
import org.springframework.stereotype.Service

@Service
class NotificationModuleApiService(
    private val reputationService: SenderReputationService,
) : NotificationModuleApi {
    override fun isUndeliverable(email: String): Boolean = reputationService.isUndeliverable(email)

    override fun currentTenantDeliverability(): DeliverabilityInfo {
        val summary = reputationService.currentTenantDeliverability()
        return DeliverabilityInfo(
            sent = summary.sent,
            bounced = summary.bounced,
            bounceRate = summary.bounceRate,
            warn = summary.warn,
        )
    }
}
