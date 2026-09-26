package com.jiku.money.internal

import com.jiku.shared.GroupSessionGate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The group-session cap of the current tenant's services plan (JIKU-174). A
 * tenant without a subscription is on the free plan, which serves one client at
 * a time; a larger group moves to a higher plan, never to a commission.
 */
@Service
class GroupSessionLimitService(
    private val subscriptions: SubscriptionRepository,
    private val properties: SubscriptionProperties,
) : GroupSessionGate {
    @Transactional(readOnly = true)
    override fun maxClientsPerSlot(): Int {
        val plan = subscriptions.findCurrent().firstOrNull()?.plan ?: return SINGLE_CLIENT
        return (properties.clientsPerSlot[plan] ?: SINGLE_CLIENT).coerceAtLeast(SINGLE_CLIENT)
    }
}

private const val SINGLE_CLIENT = 1
