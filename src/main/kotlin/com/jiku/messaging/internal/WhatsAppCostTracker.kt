package com.jiku.messaging.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Records the cost of one successfully sent WhatsApp message (JIKU-61). */
@Component
class WhatsAppCostTracker(
    private val pricing: WhatsAppPricingRepository,
    private val costs: WhatsAppMessageCostRepository,
    private val properties: WhatsAppProperties,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun record(
        referenceId: UUID?,
        eventId: UUID?,
        tenantOverride: Boolean,
        category: String,
    ) {
        val costUsdMinor = pricing.findByCategory(category)?.costUsdMinor ?: 0L
        val costGnfMinor = costUsdMinor * properties.usdToGnfRate
        costs.save(
            WhatsAppMessageCost(
                referenceId = referenceId,
                eventId = eventId,
                pool = if (tenantOverride) WhatsAppMessageCost.POOL_TENANT else WhatsAppMessageCost.POOL_PLATFORM,
                category = category,
                costUsdMinor = costUsdMinor,
                costGnfMinor = costGnfMinor,
            ),
        )
    }
}
