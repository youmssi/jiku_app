package com.jiku.money.internal

import com.jiku.shared.ServicesAccessGate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * The money module's answer to [ServicesAccessGate]: an expired subscription
 * pauses services, unless its free plan still holds the team (JIKU-303).
 */
@Component
@Transactional(readOnly = true)
class ServicesAccessGateAdapter(
    private val subscriptions: SubscriptionRepository,
    private val platformSettings: PlatformBillingSettingsService,
) : ServicesAccessGate {
    override fun servicesPaused(): Boolean {
        val row = subscriptions.findCurrent().firstOrNull() ?: return false
        return row.status == SubscriptionStatus.EXPIRED && !platformSettings.keepsTeamFree(row.plan, row.resourcesActive)
    }
}
