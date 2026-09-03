package com.jiku.messaging.internal

import org.springframework.stereotype.Component

/**
 * Blocks a WhatsApp send whose rendered body looks promotional from going out
 * as (cheap) UTILITY content (JIKU-61) — Meta reclassifies such conversations
 * as MARKETING server-side and bills 5-8x more, or flags the account for
 * category misuse. An administrator can explicitly override this (logged via
 * [WhatsAppContentOverrideRepository] / the admin audit log), acknowledging the
 * higher expected cost, rather than the app silently sending anyway.
 *
 * Deliberately not `@Transactional` itself — see [WhatsAppConversationCounter]'s
 * KDoc for why a guard that throws must not carry its own transactional advice.
 */
@Component
class WhatsAppContentGuard(
    private val properties: WhatsAppProperties,
    private val overrides: WhatsAppContentOverrideRepository,
) {
    /** [WhatsAppPricing.CATEGORY_MARKETING] or [WhatsAppPricing.CATEGORY_UTILITY]. */
    fun classify(body: String): String {
        val normalized = body.lowercase()
        val markers =
            properties.marketingMarkers
                .split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
        val flagged = markers.any { normalized.contains(it) }
        return if (flagged) WhatsAppPricing.CATEGORY_MARKETING else WhatsAppPricing.CATEGORY_UTILITY
    }

    fun assertAllowed(category: String) {
        if (category != WhatsAppPricing.CATEGORY_MARKETING) {
            return
        }
        val active = overrides.findFirstByOrderByUpdatedAtDesc()?.active ?: false
        if (!active) {
            throw WhatsAppContentPolicyException(
                "Rendered WhatsApp body contains promotional language but is being sent as UTILITY content; " +
                    "enable the admin content override to send it anyway",
            )
        }
    }
}
