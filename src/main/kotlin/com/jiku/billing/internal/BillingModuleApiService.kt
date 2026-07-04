package com.jiku.billing.internal

import com.jiku.billing.BillingAllowance
import com.jiku.billing.BillingModuleApi
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Exposes the billing module's metering to other modules (the organizer dashboard
 * for display, the invitation module for the paywall) without any of them reaching
 * into billing's tables.
 */
@Service
class BillingModuleApiService(
    private val usageService: UsageService,
) : BillingModuleApi {
    override fun allowance(eventId: UUID): BillingAllowance = usageService.allowance(eventId)

    override fun canInvite(
        eventId: UUID,
        additionalGuests: Long,
    ): Boolean = usageService.canInvite(eventId, additionalGuests)
}
