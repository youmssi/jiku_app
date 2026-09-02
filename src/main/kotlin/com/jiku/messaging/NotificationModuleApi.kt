package com.jiku.messaging

import java.time.Instant
import java.util.UUID

/**
 * The notification module's public API. Delivery itself is event-driven (see the
 * shared GuestInvitedEvent / InvitationDeliveryResult); this interface exposes only
 * read-side reputation signals other modules need: whether an address is known
 * undeliverable (for import warnings) and the current tenant's recent
 * deliverability (for the dashboard). One-directional — notification depends on no
 * other business module.
 *
 * The WhatsApp guardrail methods (JIKU-61) back the admin WhatsApp endpoints:
 * pricing is database-backed so it can be corrected without a redeploy, and the
 * content override is a platform-wide, explicitly logged admin action.
 */
interface NotificationModuleApi {
    /** True once an address has hard-bounced enough times to be treated as undeliverable. */
    fun isUndeliverable(email: String): Boolean

    /** Recent email deliverability for the current tenant (from the bound context). */
    fun currentTenantDeliverability(): DeliverabilityInfo

    fun listWhatsAppPricing(): List<WhatsAppPricingInfo>

    fun updateWhatsAppPricing(
        category: String,
        costUsdMinor: Long,
    ): WhatsAppPricingInfo

    fun whatsAppContentOverrideStatus(): WhatsAppOverrideStatus

    fun setWhatsAppContentOverride(
        active: Boolean,
        reason: String?,
        adminId: String,
    ): WhatsAppOverrideStatus

    /** WhatsApp delivery cost for one event (current tenant), aggregated across every send. */
    fun eventWhatsAppCost(eventId: UUID): WhatsAppEventCost
}

data class DeliverabilityInfo(
    val sent: Long,
    val bounced: Long,
    val bounceRate: Double,
    val warn: Boolean,
)

data class WhatsAppPricingInfo(
    val category: String,
    val costUsdMinor: Long,
)

data class WhatsAppOverrideStatus(
    val active: Boolean,
    val reason: String?,
    val activatedBy: String?,
    val activatedAt: Instant?,
)

data class WhatsAppEventCost(
    val eventId: UUID,
    val messageCount: Long,
    val costUsdMinor: Long,
    val costGnfMinor: Long,
)
