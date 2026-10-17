package com.jiku.money.internal

import com.jiku.shared.ServicesAccessGate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** The money module's answer to [ServicesAccessGate]: an expired subscription pauses services. */
@Component
@Transactional(readOnly = true)
class ServicesAccessGateAdapter(
    private val subscriptions: SubscriptionRepository,
) : ServicesAccessGate {
    override fun servicesPaused(): Boolean = subscriptions.findCurrent().firstOrNull()?.status == SubscriptionStatus.EXPIRED
}
