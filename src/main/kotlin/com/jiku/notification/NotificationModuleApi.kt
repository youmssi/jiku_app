package com.jiku.notification

/**
 * The notification module's public API. Delivery itself is event-driven (see the
 * shared GuestInvitedEvent / InvitationDeliveryResult); this interface exposes only
 * read-side reputation signals other modules need: whether an address is known
 * undeliverable (for import warnings) and the current tenant's recent
 * deliverability (for the dashboard). One-directional — notification depends on no
 * other business module.
 */
interface NotificationModuleApi {
    /** True once an address has hard-bounced enough times to be treated as undeliverable. */
    fun isUndeliverable(email: String): Boolean

    /** Recent email deliverability for the current tenant (from the bound context). */
    fun currentTenantDeliverability(): DeliverabilityInfo
}

data class DeliverabilityInfo(
    val sent: Long,
    val bounced: Long,
    val bounceRate: Double,
    val warn: Boolean,
)
