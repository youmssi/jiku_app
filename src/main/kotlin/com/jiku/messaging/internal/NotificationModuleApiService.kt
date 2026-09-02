package com.jiku.messaging.internal

import com.jiku.messaging.DeliverabilityInfo
import com.jiku.messaging.NotificationModuleApi
import com.jiku.messaging.WhatsAppEventCost
import com.jiku.messaging.WhatsAppOverrideStatus
import com.jiku.messaging.WhatsAppPricingInfo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class NotificationModuleApiService(
    private val reputationService: SenderReputationService,
    private val pricingRepository: WhatsAppPricingRepository,
    private val overrideRepository: WhatsAppContentOverrideRepository,
    private val costRepository: WhatsAppMessageCostRepository,
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

    @Transactional(readOnly = true)
    override fun listWhatsAppPricing(): List<WhatsAppPricingInfo> =
        pricingRepository.findAll().map { WhatsAppPricingInfo(it.category, it.costUsdMinor) }

    @Transactional
    override fun updateWhatsAppPricing(
        category: String,
        costUsdMinor: Long,
    ): WhatsAppPricingInfo {
        val pricing =
            requireNotNull(pricingRepository.findByCategory(category)) { "Unknown WhatsApp pricing category: $category" }
        pricing.costUsdMinor = costUsdMinor
        pricing.updatedAt = Instant.now()
        pricingRepository.save(pricing)
        return WhatsAppPricingInfo(pricing.category, pricing.costUsdMinor)
    }

    @Transactional(readOnly = true)
    override fun whatsAppContentOverrideStatus(): WhatsAppOverrideStatus {
        val override = overrideRepository.findFirstByOrderByUpdatedAtDesc()
        return WhatsAppOverrideStatus(
            active = override?.active ?: false,
            reason = override?.reason,
            activatedBy = override?.activatedBy,
            activatedAt = override?.activatedAt,
        )
    }

    @Transactional
    override fun setWhatsAppContentOverride(
        active: Boolean,
        reason: String?,
        adminId: String,
    ): WhatsAppOverrideStatus {
        val override = overrideRepository.findFirstByOrderByUpdatedAtDesc() ?: WhatsAppContentOverride()
        val now = Instant.now()
        override.active = active
        override.reason = reason
        override.activatedBy = adminId
        override.activatedAt = now
        override.updatedAt = now
        overrideRepository.save(override)
        return WhatsAppOverrideStatus(active, reason, adminId, now)
    }

    @Transactional(readOnly = true)
    override fun eventWhatsAppCost(eventId: UUID): WhatsAppEventCost =
        WhatsAppEventCost(
            eventId = eventId,
            messageCount = costRepository.countByEventId(eventId),
            costUsdMinor = costRepository.sumUsdMinorByEventId(eventId),
            costGnfMinor = costRepository.sumGnfMinorByEventId(eventId),
        )
}
